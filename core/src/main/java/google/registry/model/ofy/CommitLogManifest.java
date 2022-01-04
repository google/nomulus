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

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.replay.DatastoreOnlyEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import org.joda.time.DateTime;

/**
 * Archived Datastore transaction that can be replayed.
 *
 * <p>Entities of this kind are entity group sharded using a {@link CommitLogBucket} parent. Each
 * object that was saved during this transaction is stored in a {@link CommitLogMutation} child
 * entity.
 */
@Entity
@NotBackedUp(reason = Reason.COMMIT_LOGS)
@DeleteAfterMigration
public class CommitLogManifest extends ImmutableObject implements DatastoreOnlyEntity {

  /** Commit log manifests are parented on a random bucket. */
  @Parent
  Key<CommitLogBucket> parent;

  /**
   * The commit time (in millis since the epoch).
   *
   * <p>This will be unique among siblings sharing the same parent {@link CommitLogBucket}.
   */
  @Id
  long commitTime;

  /** Keys that were deleted in this commit. (Saves are recorded in child entities.) */
  Set<Key<?>> deletions = new LinkedHashSet<>();

  public DateTime getCommitTime() {
    return new DateTime(commitTime, UTC);
  }

  public int getBucketId() {
    return (int) parent.getId();
  }

  public ImmutableSet<Key<?>> getDeletions() {
    return nullToEmptyImmutableCopy(deletions);
  }

  public static CommitLogManifest create(
      Key<CommitLogBucket> parent, DateTime commitTime, Set<Key<?>> deletions) {
    CommitLogManifest instance = new CommitLogManifest();
    instance.parent = parent;
    instance.commitTime = commitTime.getMillis();
    instance.deletions = nullToEmptyImmutableCopy(deletions);
    return instance;
  }

  public static Key<CommitLogManifest> createKey(Key<CommitLogBucket> parent, DateTime commitTime) {
    return Key.create(parent, CommitLogManifest.class, commitTime.getMillis());
  }

  /** Returns the commit time encoded into a CommitLogManifest key. */
  public static DateTime extractCommitTime(Key<CommitLogManifest> manifestKey) {
    return new DateTime(manifestKey.getId(), UTC);
  }
}
