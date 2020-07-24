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

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.toMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.BackupUtils.serializeEntity;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.model.ofy.CommitLogBucket.getBucketIds;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.tools.cloudstorage.GcsFileOptions;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.TestObject;
import google.registry.util.Retrier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RestoreCommitLogsAction}. */
public class RestoreCommitLogsActionTest {

  private static final String GCS_BUCKET = "gcs bucket";

  private final DateTime now = DateTime.now(UTC);
  private final RestoreCommitLogsAction action = new RestoreCommitLogsAction();
  private final GcsService gcsService = createGcsService();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  @BeforeEach
  void beforeEach() {
    action.gcsService = gcsService;
    action.dryRun = false;
    action.datastoreService = DatastoreServiceFactory.getDatastoreService();
    action.fromTime = now.minusMillis(1);
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 1);
    action.diffLister = new GcsDiffFileLister();
    action.diffLister.gcsService = gcsService;
    action.diffLister.gcsBucket = GCS_BUCKET;
    action.diffLister.executor = newDirectExecutorService();
  }

  @Test
  void testRestore_multipleDiffFiles() throws Exception {
    ofy().saveWithoutBackup().entities(
        TestObject.create("previous to keep"),
        TestObject.create("previous to delete")).now();
    // Create 3 transactions, across two diff files.
    // Before: {"previous to keep", "previous to delete"}
    // 1a: Add {"a", "b"}, Delete {"previous to delete"}
    // 1b: Add {"c", "d"}, Delete {"a"}
    // 2:  Add {"e", "f"}, Delete {"c"}
    // After:  {"previous to keep", "b", "d", "e", "f"}
    Key<CommitLogManifest> manifest1aKey =
        CommitLogManifest.createKey(getBucketKey(1), now.minusMinutes(3));
    Key<CommitLogManifest> manifest1bKey =
        CommitLogManifest.createKey(getBucketKey(2), now.minusMinutes(2));
    Key<CommitLogManifest> manifest2Key =
        CommitLogManifest.createKey(getBucketKey(1), now.minusMinutes(1));
    saveDiffFileNotToRestore(now.minusMinutes(2));
    Iterable<ImmutableObject> file1CommitLogs = saveDiffFile(
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(
            getBucketKey(1),
            now.minusMinutes(3),
            ImmutableSet.of(Key.create(TestObject.create("previous to delete")))),
        CommitLogMutation.create(manifest1aKey, TestObject.create("a")),
        CommitLogMutation.create(manifest1aKey, TestObject.create("b")),
        CommitLogManifest.create(
            getBucketKey(2),
            now.minusMinutes(2),
            ImmutableSet.of(Key.create(TestObject.create("a")))),
        CommitLogMutation.create(manifest1bKey, TestObject.create("c")),
        CommitLogMutation.create(manifest1bKey, TestObject.create("d")));
    Iterable<ImmutableObject> file2CommitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(
            getBucketKey(1),
            now.minusMinutes(1),
            ImmutableSet.of(Key.create(TestObject.create("c")))),
        CommitLogMutation.create(manifest2Key, TestObject.create("e")),
        CommitLogMutation.create(manifest2Key, TestObject.create("f")));
    action.fromTime = now.minusMinutes(1).minusMillis(1);
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep", "b", "d", "e", "f");
    assertInDatastore(file1CommitLogs);
    assertInDatastore(file2CommitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now.minusMinutes(1), 2, now.minusMinutes(2)));
  }

  @Test
  void testRestore_noManifests() throws Exception {
    ofy().saveWithoutBackup().entity(
        TestObject.create("previous to keep")).now();
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(createCheckpoint(now));
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of());
  }

  @Test
  void testRestore_manifestWithNoDeletions() throws Exception {
    ofy().saveWithoutBackup().entity(TestObject.create("previous to keep")).now();
    Key<CommitLogBucket> bucketKey = getBucketKey(1);
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(bucketKey, now);
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(bucketKey, now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("a")),
        CommitLogMutation.create(manifestKey, TestObject.create("b")));
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep", "a", "b");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
}

  @Test
  void testRestore_manifestWithNoMutations() throws Exception {
    ofy().saveWithoutBackup().entities(
        TestObject.create("previous to keep"),
        TestObject.create("previous to delete")).now();
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(
            getBucketKey(1),
            now,
            ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  // This is a pathological case that shouldn't be possible, but we should be robust to it.
  @Test
  void testRestore_manifestWithNoMutationsOrDeletions() throws Exception {
    ofy().saveWithoutBackup().entities(
        TestObject.create("previous to keep")).now();
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(getBucketKey(1), now, null));
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  @Test
  void testRestore_mutateExistingEntity() throws Exception {
    ofy().saveWithoutBackup().entity(TestObject.create("existing", "a")).now();
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(getBucketKey(1), now);
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(getBucketKey(1), now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("existing", "b")));
    action.run();
    ofy().clearSessionCache();
    assertThat(ofy().load().entity(TestObject.create("existing")).now().getField()).isEqualTo("b");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  // This should be harmless; deletes are idempotent.
  @Test
  void testRestore_deleteMissingEntity() throws Exception {
    ofy().saveWithoutBackup().entity(TestObject.create("previous to keep", "a")).now();
    saveDiffFileNotToRestore(now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(
            getBucketKey(1),
            now,
            ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    action.run();
    ofy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertCommitLogBuckets(ImmutableMap.of(1, now));
    assertInDatastore(CommitLogCheckpointRoot.create(now));
  }

  private CommitLogCheckpoint createCheckpoint(DateTime now) {
    return CommitLogCheckpoint.create(now, toMap(getBucketIds(), x -> now));
  }

  private Iterable<ImmutableObject> saveDiffFile(
      CommitLogCheckpoint checkpoint, ImmutableObject... entities) throws IOException {
    DateTime now = checkpoint.getCheckpointTime();
    List<ImmutableObject> allEntities = Lists.asList(checkpoint, entities);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (ImmutableObject entity : allEntities) {
      serializeEntity(entity, output);
    }
    gcsService.createOrReplace(
        new GcsFilename(GCS_BUCKET, DIFF_FILE_PREFIX + now),
        new GcsFileOptions.Builder()
            .addUserMetadata(LOWER_BOUND_CHECKPOINT, now.minusMinutes(1).toString())
            .build(),
        ByteBuffer.wrap(output.toByteArray()));
    return allEntities;
  }

  private void saveDiffFileNotToRestore(DateTime now) throws Exception {
    saveDiffFile(
        createCheckpoint(now),
        CommitLogManifest.create(getBucketKey(1), now, null),
        CommitLogMutation.create(
            CommitLogManifest.createKey(getBucketKey(1), now),
            TestObject.create("should not be restored")));
  }

  private void assertExpectedIds(String... ids) {
    assertThat(transform(ofy().load().type(TestObject.class), TestObject::getId))
        .containsExactly((Object[]) ids);
  }

  private void assertInDatastore(ImmutableObject entity) {
    assertThat(ofy().load().entity(entity).now()).isEqualTo(entity);
  }

  private void assertInDatastore(Iterable<? extends ImmutableObject> entities) {
    assertThat(ofy().load().entities(entities).values()).containsExactlyElementsIn(entities);
  }

  private void assertCommitLogBuckets(Map<Integer, DateTime> bucketIdsAndTimestamps) {
    Map<Long, CommitLogBucket> buckets = ofy().load()
        .type(CommitLogBucket.class)
        .ids(Longs.asList(Longs.toArray(CommitLogBucket.getBucketIds())));
    assertThat(buckets).hasSize(bucketIdsAndTimestamps.size());
    for (Entry<Integer, DateTime> bucketIdAndTimestamp : bucketIdsAndTimestamps.entrySet()) {
      assertThat(buckets.get((long) bucketIdAndTimestamp.getKey()).getLastWrittenTime())
          .isEqualTo(bucketIdAndTimestamp.getValue());
    }
  }
}
