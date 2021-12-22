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

import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.replay.DatastoreOnlyEntity;
import org.joda.time.DateTime;

/** Singleton parent entity for all commit log checkpoints. */
@Entity
@NotBackedUp(reason = Reason.COMMIT_LOGS)
@DeleteAfterMigration
public class CommitLogCheckpointRoot extends ImmutableObject implements DatastoreOnlyEntity {

  public static final long SINGLETON_ID = 1;  // There is always exactly one of these.

  @Id
  long id = SINGLETON_ID;

  /** Singleton key for CommitLogCheckpointParent. */
  public static Key<CommitLogCheckpointRoot> getKey() {
    return Key.create(CommitLogCheckpointRoot.class, SINGLETON_ID);
  }

  /** The timestamp of the last {@link CommitLogCheckpoint} written. */
  DateTime lastWrittenTime = START_OF_TIME;

  public DateTime getLastWrittenTime() {
    return lastWrittenTime;
  }

  public static CommitLogCheckpointRoot loadRoot() {
    CommitLogCheckpointRoot root = auditedOfy().load().key(getKey()).now();
    return root == null ? new CommitLogCheckpointRoot() : root;
  }

  public static CommitLogCheckpointRoot create(DateTime lastWrittenTime) {
    CommitLogCheckpointRoot instance = new CommitLogCheckpointRoot();
    instance.lastWrittenTime = lastWrittenTime;
    return instance;
  }
}
