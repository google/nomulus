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

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogManifest;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Base class for {@link Input} classes that map over {@link CommitLogManifest}. */
@DeleteAfterMigration
public class CommitLogManifestInput extends Input<Key<CommitLogManifest>> {

  private static final long serialVersionUID = 6744322799131602384L;

  /**
   * Cutoff date for result.
   *
   * <p>If present, all resulting CommitLogManifest will be dated prior to this date. This can't be
   * of type {@code Optional<DateTime>} because Optional purposely isn't Serializable.
   */
  @Nullable
  private final DateTime olderThan;

  public CommitLogManifestInput() {
    this.olderThan = null;
  }

  public CommitLogManifestInput(@Nullable DateTime olderThan) {
    this.olderThan = olderThan;
  }

  @Override
  public List<InputReader<Key<CommitLogManifest>>> createReaders() {
    ImmutableList.Builder<InputReader<Key<CommitLogManifest>>> readers =
        new ImmutableList.Builder<>();
    for (Key<CommitLogBucket> bucketKey : CommitLogBucket.getAllBucketKeys()) {
      readers.add(bucketToReader(bucketKey));
    }
    return readers.build();
  }

  private InputReader<Key<CommitLogManifest>> bucketToReader(Key<CommitLogBucket> bucketKey) {
    return new CommitLogManifestReader(bucketKey, olderThan);
  }
}
