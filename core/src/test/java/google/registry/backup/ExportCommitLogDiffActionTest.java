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

package google.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.NUM_TRANSACTIONS;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.UPPER_BOUND_CHECKPOINT;
import static google.registry.backup.BackupUtils.deserializeEntities;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.gcs.GcsUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestObject;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ExportCommitLogDiffAction}. */
public class ExportCommitLogDiffActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final DateTime now = DateTime.now(UTC);
  private final DateTime oneMinuteAgo = now.minusMinutes(1);

  private final ExportCommitLogDiffAction task = new ExportCommitLogDiffAction();

  @BeforeEach
  void beforeEach() {
    task.gcsUtils = gcsUtils;
    task.gcsBucket = "gcs bucket";
    task.batchSize = 5;
  }

  @Test
  void testRun_noCommitHistory_onlyUpperCheckpointExported() throws Exception {
    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    persistResource(CommitLogCheckpoint.create(
        oneMinuteAgo,
        ImmutableMap.of(1, oneMinuteAgo, 2, oneMinuteAgo, 3, oneMinuteAgo)));
    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(1, now, 2, now, 3, now)));

    // Don't persist any manifests or mutations.

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "0");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported).containsExactly(upperCheckpoint);
  }

  @Test
  void testRun_regularCommitHistory_exportsCorrectCheckpointDiff() throws Exception {
    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    // Persist the lower and upper checkpoints, with 3 buckets each and staggered times. We respect
    // the real invariant that the time for bucket n in the lower checkpoint is <= the time for
    // that bucket in the upper.
    persistResource(CommitLogCheckpoint.create(
        oneMinuteAgo,
        ImmutableMap.of(
            1, oneMinuteAgo,
            2, oneMinuteAgo.minusDays(1),
            3, oneMinuteAgo.minusDays(2))));
    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(
            1, now,
            2, now.minusDays(1),
            3, oneMinuteAgo.minusDays(2))));  // Note that this matches the lower bound.

    // Persist some fake commit log manifests.
    // These shouldn't be in the diff because the lower bound is exclusive.
    persistManifestAndMutation(1, oneMinuteAgo);
    persistManifestAndMutation(2, oneMinuteAgo.minusDays(1));
    persistManifestAndMutation(3, oneMinuteAgo.minusDays(2));  // Even though it's == upper bound.
    // These shouldn't be in the diff because they are above the upper bound.
    persistManifestAndMutation(1, now.plusMillis(1));
    persistManifestAndMutation(2, now.minusDays(1).plusMillis(1));
    persistManifestAndMutation(3, oneMinuteAgo.minusDays(2).plusMillis(1));
    // These should be in the diff because they are between the bounds. (Not possible for bucket 3.)
    persistManifestAndMutation(1, now.minusMillis(1));
    persistManifestAndMutation(2, now.minusDays(1).minusMillis(1));
    // These should be in the diff because they are at the upper bound. (Not possible for bucket 3.)
    persistManifestAndMutation(1, now);
    persistManifestAndMutation(2, now.minusDays(1));

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "4");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported.get(0)).isEqualTo(upperCheckpoint);
    // We expect these manifests, in time order, with matching mutations.
    CommitLogManifest manifest1 = createManifest(2, now.minusDays(1).minusMillis(1));
    CommitLogManifest manifest2 = createManifest(2, now.minusDays(1));
    CommitLogManifest manifest3 = createManifest(1, now.minusMillis(1));
    CommitLogManifest manifest4 = createManifest(1, now);
    assertThat(exported).containsExactly(
        upperCheckpoint,
        manifest1,
        createMutation(manifest1),
        manifest2,
        createMutation(manifest2),
        manifest3,
        createMutation(manifest3),
        manifest4,
        createMutation(manifest4))
            .inOrder();
  }

  @Test
  void testRun_simultaneousTransactions_bothExported() throws Exception {
    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    persistResource(CommitLogCheckpoint.create(
        oneMinuteAgo,
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));
    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(1, now, 2, now, 3, now)));

    // Persist some fake commit log manifests that are at the same time but in different buckets.
    persistManifestAndMutation(1, oneMinuteAgo);
    persistManifestAndMutation(2, oneMinuteAgo);
    persistManifestAndMutation(1, now);
    persistManifestAndMutation(2, now);

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "4");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported.get(0)).isEqualTo(upperCheckpoint);
    // We expect these manifests, in the order below, with matching mutations.
    CommitLogManifest manifest1 = createManifest(1, oneMinuteAgo);
    CommitLogManifest manifest2 = createManifest(2, oneMinuteAgo);
    CommitLogManifest manifest3 = createManifest(1, now);
    CommitLogManifest manifest4 = createManifest(2, now);
    assertThat(exported).containsExactly(
        upperCheckpoint,
        manifest1,
        createMutation(manifest1),
        manifest2,
        createMutation(manifest2),
        manifest3,
        createMutation(manifest3),
        manifest4,
        createMutation(manifest4))
            .inOrder();
  }

  @Test
  void testRun_exportsAcrossMultipleBatches() throws Exception {
    task.batchSize = 2;
    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    persistResource(CommitLogCheckpoint.create(
        oneMinuteAgo,
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));
    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(1, now, 2, now, 3, now)));

    // Persist some fake commit log manifests.
    persistManifestAndMutation(1, oneMinuteAgo);
    persistManifestAndMutation(2, oneMinuteAgo);
    persistManifestAndMutation(3, oneMinuteAgo);
    persistManifestAndMutation(1, now);
    persistManifestAndMutation(2, now);
    persistManifestAndMutation(3, now);

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "6");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported.get(0)).isEqualTo(upperCheckpoint);
    // We expect these manifests, in the order below, with matching mutations.
    CommitLogManifest manifest1 = createManifest(1, oneMinuteAgo);
    CommitLogManifest manifest2 = createManifest(2, oneMinuteAgo);
    CommitLogManifest manifest3 = createManifest(3, oneMinuteAgo);
    CommitLogManifest manifest4 = createManifest(1, now);
    CommitLogManifest manifest5 = createManifest(2, now);
    CommitLogManifest manifest6 = createManifest(3, now);
    assertThat(exported).containsExactly(
        upperCheckpoint,
        manifest1,
        createMutation(manifest1),
        manifest2,
        createMutation(manifest2),
        manifest3,
        createMutation(manifest3),
        manifest4,
        createMutation(manifest4),
        manifest5,
        createMutation(manifest5),
        manifest6,
        createMutation(manifest6))
            .inOrder();
  }

  @Test
  void testRun_checkpointDiffWithNeverTouchedBuckets_exportsCorrectly() throws Exception {
    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    persistResource(CommitLogCheckpoint.create(
        oneMinuteAgo,
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));
    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));

    // Don't persist any commit log manifests; we're just checking that the task runs correctly
    // even if the upper timestamp contains START_OF_TIME values.

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "0");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    // We expect no manifests or mutations, only the upper checkpoint.
    assertThat(exported).containsExactly(upperCheckpoint);
  }

  @Test
  void testRun_checkpointDiffWithNonExistentBucketTimestamps_exportsCorrectly() throws Exception {
    // Non-existent bucket timestamps can exist when the commit log bucket count was increased
    // recently.

    task.lowerCheckpointTime = oneMinuteAgo;
    task.upperCheckpointTime = now;

    // No lower checkpoint times are persisted for buckets 2 and 3 (simulating a recent increase in
    // the number of commit log buckets from 1 to 3), so all mutations on buckets 2 and 3, even
    // those older than the lower checkpoint, will be exported.
    persistResource(
        CommitLogCheckpoint.createForTest(oneMinuteAgo, ImmutableMap.of(1, oneMinuteAgo)));
    CommitLogCheckpoint upperCheckpoint =
        persistResource(
            CommitLogCheckpoint.create(
                now,
                ImmutableMap.of(
                    1, now,
                    2, now.minusDays(1),
                    3, oneMinuteAgo.minusDays(2))));

    // These shouldn't be in the diff because the lower bound is exclusive.
    persistManifestAndMutation(1, oneMinuteAgo);
    // These shouldn't be in the diff because they are above the upper bound.
    persistManifestAndMutation(1, now.plusMillis(1));
    persistManifestAndMutation(2, now.minusDays(1).plusMillis(1));
    persistManifestAndMutation(3, oneMinuteAgo.minusDays(2).plusMillis(1));
    // These should be in the diff because they happened after START_OF_TIME on buckets with
    // non-existent timestamps.
    persistManifestAndMutation(2, oneMinuteAgo.minusDays(1));
    persistManifestAndMutation(3, oneMinuteAgo.minusDays(2));
    // These should be in the diff because they are between the bounds.
    persistManifestAndMutation(1, now.minusMillis(1));
    persistManifestAndMutation(2, now.minusDays(1).minusMillis(1));
    // These should be in the diff because they are at the upper bound.
    persistManifestAndMutation(1, now);
    persistManifestAndMutation(2, now.minusDays(1));

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            oneMinuteAgo.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "6");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported.get(0)).isEqualTo(upperCheckpoint);
    // We expect these manifests, in time order, with matching mutations.
    CommitLogManifest manifest1 = createManifest(3, oneMinuteAgo.minusDays(2));
    CommitLogManifest manifest2 = createManifest(2, oneMinuteAgo.minusDays(1));
    CommitLogManifest manifest3 = createManifest(2, now.minusDays(1).minusMillis(1));
    CommitLogManifest manifest4 = createManifest(2, now.minusDays(1));
    CommitLogManifest manifest5 = createManifest(1, now.minusMillis(1));
    CommitLogManifest manifest6 = createManifest(1, now);
    assertThat(exported)
        .containsExactly(
            upperCheckpoint,
            manifest1,
            createMutation(manifest1),
            manifest2,
            createMutation(manifest2),
            manifest3,
            createMutation(manifest3),
            manifest4,
            createMutation(manifest4),
            manifest5,
            createMutation(manifest5),
            manifest6,
            createMutation(manifest6))
        .inOrder();
  }

  @Test
  void testRun_exportingFromStartOfTime_exportsAllCommits() throws Exception {
    task.lowerCheckpointTime = START_OF_TIME;
    task.upperCheckpointTime = now;

    CommitLogCheckpoint upperCheckpoint = persistResource(CommitLogCheckpoint.create(
        now,
        ImmutableMap.of(1, now, 2, now, 3, now)));

    // Persist some fake commit log manifests.
    persistManifestAndMutation(1, START_OF_TIME.plusMillis(1));  // Oldest possible manifest time.
    persistManifestAndMutation(2, oneMinuteAgo);
    persistManifestAndMutation(3, now);

    task.run();

    BlobId expectedFilename = BlobId.of("gcs bucket", "commit_diff_until_" + now);
    assertWithMessage("GCS file not found: " + expectedFilename)
        .that(gcsUtils.existsAndNotEmpty(expectedFilename))
        .isTrue();
    assertThat(gcsUtils.getMetadata(expectedFilename))
        .containsExactly(
            LOWER_BOUND_CHECKPOINT,
            START_OF_TIME.toString(),
            UPPER_BOUND_CHECKPOINT,
            now.toString(),
            NUM_TRANSACTIONS,
            "3");
    List<ImmutableObject> exported = deserializeEntities(gcsUtils.readBytesFrom(expectedFilename));
    assertThat(exported.get(0)).isEqualTo(upperCheckpoint);
    // We expect these manifests, in the order below, with matching mutations.
    CommitLogManifest manifest1 = createManifest(1, START_OF_TIME.plusMillis(1));
    CommitLogManifest manifest2 = createManifest(2, oneMinuteAgo);
    CommitLogManifest manifest3 = createManifest(3, now);
    assertThat(exported).containsExactly(
        upperCheckpoint,
        manifest1,
        createMutation(manifest1),
        manifest2,
        createMutation(manifest2),
        manifest3,
        createMutation(manifest3))
            .inOrder();
  }

  private CommitLogManifest createManifest(int bucketNum, DateTime commitTime) {
    return CommitLogManifest.create(CommitLogBucket.getBucketKey(bucketNum), commitTime, null);
  }

  private CommitLogMutation createMutation(CommitLogManifest manifest) {
    return CommitLogMutation.create(
        Key.create(manifest),
        TestObject.create(manifest.getCommitTime().toString()));
  }

  private void persistManifestAndMutation(int bucketNum, DateTime commitTime) {
    persistResource(
        createMutation(persistResource(createManifest(bucketNum, commitTime))));
  }
}
