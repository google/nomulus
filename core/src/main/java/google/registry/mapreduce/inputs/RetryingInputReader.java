// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mapreduce.inputs;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.DatastoreTimeoutException;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * A reader over objectify query that retries reads on failure.
 *
 * <p>When doing a mapreduce over a large number of elements from Datastore, the random
 * DatastoreTimeoutExceptions that happen sometimes can eventually add up and cause the entire
 * mapreduce to fail.
 *
 * <p>This base RetryingInputReader will automatically retry any DatastoreTimeoutException to
 * minimize the failures.
 *
 * <p>I is the internal Objectify read type, while T is the InputReader return type.
 */
@DeleteAfterMigration
abstract class RetryingInputReader<I, T> extends InputReader<T> {

  private static final long serialVersionUID = -4897677478541818899L;
  private static final Retrier retrier = new Retrier(new SystemSleeper(), 5);
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Nullable private Cursor cursor;
  private int total;
  private int loaded;

  private transient QueryResultIterator<I> queryIterator;

  /**
   * Return the iterator over Query results, starting at the cursor location.
   *
   * <p>Must always return an iterator over the same query.
   *
   * <p>The underlying {@link Query} must have an ancestor filter, so that it is strongly
   * consistent. According to the documentation at
   * https://cloud.google.com/appengine/docs/java/datastore/queries#Java_Data_consistency
   *
   * <p>"strongly consistent queries are always transactionally consistent". However, each time we
   * restart the query at a cursor we have a new effective query, and "if the results for a query
   * change between uses of a cursor, the query notices only changes that occur in results after the
   * cursor. If a new result appears before the cursor's position for the query, it will not be
   * returned when the results after the cursor are fetched."
   *
   * <p>What this means in practice is that entities that are created after the initial query begins
   * may or may not be seen by this reader, depending on whether the query was paused and restarted
   * with a cursor before it would have reached the new entity.
   *
   * @param cursor the initial location for the iterator to start from. If null - start from
   *     beginning.
   */
  public abstract QueryResultIterator<I> getQueryIterator(@Nullable Cursor cursor);

  /**
   * Return the total number of elements the iterator goes over.
   *
   * <p>The results are cached - this function will only be called once on the start of the shard,
   * or when the iterator is reset.
   *
   * <p>The results are only used for debugging / progress display. It is safe to return 0.
   */
  public abstract int getTotal();

  /**
   * Return the next item of this InputReader.
   *
   * <p>You probably want to use {@link #nextQueryResult} internally when preparing the next item.
   * It is OK to call {@link #nextQueryResult} multiple times.
   */
  @Override
  public abstract T next();

  /** Called once at start. Cache the expected size. */
  @Override
  public void beginShard() {
    total = getTotal();
  }

  /** Called every time we are deserialized. Create a new query or resume an existing one. */
  @Override
  public void beginSlice() {
    queryIterator = getQueryIterator(cursor);
  }

  /** Called occasionally alongside {@link #next}. */
  @Override
  public Double getProgress() {
    // Cap progress at 1.0, since the query's count() can increase during the run of the mapreduce
    // if more entities are written, but we've cached the value once in "total".
    return Math.min(1.0, ((double) loaded) / Math.max(1, total));
  }

  /** Called before we are serialized. Save a serializable cursor for this query. */
  @Override
  public void endSlice() {
    cursor = queryIterator.getCursor();
  }

  /**
   * Get the next item from the query results.
   *
   * <p>Use this to create the next() function.
   *
   * @throws NoSuchElementException if there are no more elements.
   */
  protected final I nextQueryResult() {
    cursor = queryIterator.getCursor();
    loaded++;
    try {
      return retrier.callWithRetry(
          () -> queryIterator.next(),
          (thrown, failures, maxAttempts) -> {
            checkNotNull(cursor, "Can't retry because cursor is null. Giving up.");
            logger.atInfo().withCause(thrown).log(
                "Retriable failure while reading item %d/%d - attempt %d/%d.",
                loaded, total, failures, maxAttempts);
            queryIterator = getQueryIterator(cursor);
          },
          DatastoreTimeoutException.class);
    } catch (NoSuchElementException e) {
      // We expect NoSuchElementException to be thrown, and it isn't an error. Just rethrow.
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(
          String.format("Got an unrecoverable failure while reading item %d/%d.", loaded, total),
          e);
    } finally {
      auditedOfy().clearSessionCache();
    }
  }

  /**
   * Utility function to start a query from a given nullable cursor.
   *
   * @param query the query to work on
   * @param cursor the location to start from. If null - starts from the beginning.
   */
  public static <T> Query<T> startQueryAt(Query<T> query, @Nullable Cursor cursor) {
    return (cursor == null) ? query : query.startAt(cursor);
  }
}
