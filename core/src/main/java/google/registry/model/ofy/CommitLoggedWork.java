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

package google.registry.model.ofy;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.Clock;
import java.util.function.Supplier;

/** Wrapper for {@link Supplier} that associates a time with each attempt. */
@DeleteAfterMigration
public class CommitLoggedWork<R> implements Runnable {

  private final Supplier<R> work;
  private final Clock clock;

  /**
   * Temporary place to store the result of a non-void work.
   *
   * <p>We don't want to return the result directly because we are going to try to recover from a
   * {@link com.google.appengine.api.datastore.DatastoreTimeoutException} deep inside Objectify when
   * it tries to commit the transaction. When an exception is thrown the return value would be lost,
   * but sometimes we will be able to determine that we actually succeeded despite the timeout, and
   * we'll want to get the result.
   */
  private R result;

  /**
   * Temporary place to store the mutations belonging to the commit log manifest.
   *
   * <p>These are used along with the manifest to determine whether a transaction succeeded.
   */
  protected ImmutableSet<ImmutableObject> mutations = ImmutableSet.of();

  /** Lifecycle marker to track whether {@link #run} has been called. */
  private boolean runCalled;

  CommitLoggedWork(Supplier<R> work, Clock clock) {
    this.work = work;
    this.clock = clock;
  }

  protected TransactionInfo createNewTransactionInfo() {
    return new TransactionInfo(clock.nowUtc());
  }

  boolean hasRun() {
    return runCalled;
  }

  R getResult() {
    checkState(runCalled, "Cannot call getResult() before run()");
    return result;
  }

  @Override
  public void run() {
    // The previous time will generally be null, except when using transactNew.
    TransactionInfo previous = Ofy.TRANSACTION_INFO.get();
    // Set the time to be used for "now" within the transaction.
    try {
      Ofy.TRANSACTION_INFO.set(createNewTransactionInfo());
      result = work.get();
    } finally {
      Ofy.TRANSACTION_INFO.set(previous);
    }
    runCalled = true;
  }
}
