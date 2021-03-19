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

package google.registry.mapreduce.inputs;

import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.tools.mapreduce.InputReader;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogManifest;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** {@link InputReader} that maps over {@link CommitLogManifest}. */
class CommitLogManifestReader
    extends RetryingInputReader<Key<CommitLogManifest>, Key<CommitLogManifest>> {

  /**
   * Memory estimation for this reader.
   *
   * Elements are relatively small (parent key, Id, and a set of deleted keys), so this should be
   * more than enough.
   */
  private static final long MEMORY_ESTIMATE = 100 * 1024;

  private static final long serialVersionUID = 6215490573108252100L;

  private final Key<CommitLogBucket> bucketKey;

  /**
   * Cutoff date for result.
   *
   * If present, all resulting CommitLogManifest will be dated prior to this date.
   */
  @Nullable
  private final DateTime olderThan;

  CommitLogManifestReader(Key<CommitLogBucket> bucketKey, @Nullable DateTime olderThan) {
    this.bucketKey = bucketKey;
    this.olderThan = olderThan;
  }

  @Override
  public QueryResultIterator<Key<CommitLogManifest>> getQueryIterator(@Nullable Cursor cursor) {
    return startQueryAt(createBucketQuery(), cursor).keys().iterator();
  }

  @Override
  public int getTotal() {
    return createBucketQuery().count();
  }

  /** Query for children of this bucket. */
  Query<CommitLogManifest> createBucketQuery() {
    Query<CommitLogManifest> query = ofy().load().type(CommitLogManifest.class).ancestor(bucketKey);
    if (olderThan != null) {
      query = query.filterKey(
          "<",
          Key.create(bucketKey, CommitLogManifest.class, olderThan.getMillis()));
    }
    return query;
  }

  /** Returns the estimated memory that will be used by this reader in bytes. */
  @Override
  public long estimateMemoryRequirement() {
    return MEMORY_ESTIMATE;
  }

  /**
   * Get the next {@link CommitLogManifest} from the query.
   *
   * @throws NoSuchElementException if there are no more elements.
   */
  @Override
  public Key<CommitLogManifest> next() {
    return nextQueryResult();
  }
}
