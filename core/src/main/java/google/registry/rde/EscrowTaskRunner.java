// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rde;

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.flogger.FluentLogger;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Runner applying guaranteed reliability to an {@link EscrowTask}.
 *
 * <p>This class implements the <i>Locking Rolling Cursor</i> pattern, which solves the problem of
 * how to reliably execute App Engine tasks which can't be made idempotent.
 *
 * <p>{@link LockHandler} is used to ensure only one task executes at a time for a given
 * {@code LockedCursorTask} subclass + TLD combination. This is necessary because App Engine tasks
 * might double-execute. Normally tasks solve this by being idempotent, but that's not possible for
 * RDE, which writes to a GCS filename with a deterministic name. So Datastore is used to to
 * guarantee isolation. If we can't acquire the lock, it means the task is already running, so
 * {@link NoContentException} is thrown to cancel the task.
 *
 * <p>The specific date for which the deposit is generated depends on the current position of the
 * {@link Cursor}. If the cursor is set to tomorrow, we do nothing and return 204 No Content. If the
 * cursor is set to today, then we create a deposit for today and advance the cursor. If the cursor
 * is set to yesterday or earlier, then we create a deposit for that date, advance the cursor, but
 * we <i>do not</i> make any attempt to catch the cursor up to the current time. Therefore <b>you
 * must</b> set the cron interval to something less than the desired interval, so the cursor can
 * catch up. For example, if the task is supposed to run daily, you should configure cron to execute
 * it every twelve hours, or possibly less.
 */
class EscrowTaskRunner {

  /** Callback interface for objects managed by {@link EscrowTaskRunner}. */
  public interface EscrowTask {

    /**
     * Performs task logic while the lock is held.
     *
     * @param watermark the logical time for a point-in-time view of Datastore
     */
    void runWithLock(DateTime watermark) throws Exception;
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject LockHandler lockHandler;
  @Inject EscrowTaskRunner() {}

  /**
   * Acquires lock, checks cursor, invokes {@code task}, and advances cursor.
   *
   * @param task the task to run
   * @param registry the {@link Registry} that we are performing escrow for
   * @param timeout time when we assume failure, kill the task (and instance) and release the lock
   * @param cursorType the cursor to advance on success, indicating the next required runtime
   * @param interval how far to advance the cursor (e.g. a day for RDE, a week for BRDA)
   */
  void lockRunAndRollForward(
      final EscrowTask task,
      final Registry registry,
      Duration timeout,
      final CursorType cursorType,
      final Duration interval) {
    Callable<Void> lockRunner =
        () -> {
          logger.atInfo().log("TLD: %s", registry.getTld());
          DateTime startOfToday = clock.nowUtc().withTimeAtStartOfDay();
          Cursor cursor = ofy().load().key(Cursor.createKey(cursorType, registry)).now();
          final DateTime nextRequiredRun = (cursor == null ? startOfToday : cursor.getCursorTime());
          if (nextRequiredRun.isAfter(startOfToday)) {
            throw new NoContentException("Already completed");
          }
          logger.atInfo().log("Current cursor is: %s", nextRequiredRun);
          task.runWithLock(nextRequiredRun);
          DateTime nextRun = nextRequiredRun.plus(interval);
          logger.atInfo().log("Rolling cursor forward to %s.", nextRun);
          tm().transact(() -> ofy().save().entity(Cursor.create(cursorType, nextRun, registry)));
          return null;
        };
    String lockName = String.format("EscrowTaskRunner %s", task.getClass().getSimpleName());
    if (!lockHandler.executeWithLocks(lockRunner, registry.getTldStr(), timeout, lockName)) {
      // This will happen if either: a) the task is double-executed; b) the task takes a long time
      // to run and the retry task got executed while the first one is still running. In both
      // situations the safest thing to do is to just return 503 so the task gets retried later.
      throw new ServiceUnavailableException(
          String.format("Lock in use: %s for TLD: %s", lockName, registry.getTldStr()));
    }
  }
}
