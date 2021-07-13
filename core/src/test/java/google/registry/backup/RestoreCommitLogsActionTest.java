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

import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.toMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.BackupUtils.serializeEntity;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.model.ofy.CommitLogBucket.getBucketIds;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.common.primitives.Longs;
import com.googlecode.objectify.Key;
import google.registry.gcs.GcsUtils;
import google.registry.gcs.backport.LocalStorageHelper;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RestoreCommitLogsAction}. */
public class RestoreCommitLogsActionTest {

  static final String GCS_BUCKET = "gcs bucket";

  private final DateTime now = DateTime.now(UTC);
  private final RestoreCommitLogsAction action = new RestoreCommitLogsAction();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  @BeforeEach
  void beforeEach() {
    action.gcsUtils = gcsUtils;
    action.dryRun = false;
    action.datastoreService = DatastoreServiceFactory.getDatastoreService();
    action.fromTime = now.minusMillis(1);
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 1);
    action.defaultGcsBucket = GCS_BUCKET;
    action.gcsBucketOverride = Optional.empty();
    action.diffLister = new GcsDiffFileLister();
    action.diffLister.gcsUtils = gcsUtils;
    action.diffLister.executor = newDirectExecutorService();
  }

  @Test
  void testRestore_multipleDiffFiles() throws Exception {
    auditedOfy()
        .saveWithoutBackup()
        .entities(TestObject.create("previous to keep"), TestObject.create("previous to delete"))
        .now();
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
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(2));
    Iterable<ImmutableObject> file1CommitLogs =
        saveDiffFile(
            gcsUtils,
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
    Iterable<ImmutableObject> file2CommitLogs =
        saveDiffFile(
            gcsUtils,
            createCheckpoint(now),
            CommitLogManifest.create(
                getBucketKey(1),
                now.minusMinutes(1),
                ImmutableSet.of(Key.create(TestObject.create("c")))),
            CommitLogMutation.create(manifest2Key, TestObject.create("e")),
            CommitLogMutation.create(manifest2Key, TestObject.create("f")));
    action.fromTime = now.minusMinutes(1).minusMillis(1);
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep", "b", "d", "e", "f");
    assertInDatastore(file1CommitLogs);
    assertInDatastore(file2CommitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now.minusMinutes(1), 2, now.minusMinutes(2)));
  }

  @Test
  void testRestore_noManifests() throws Exception {
    auditedOfy().saveWithoutBackup().entity(TestObject.create("previous to keep")).now();
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs = saveDiffFile(gcsUtils, createCheckpoint(now));
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of());
  }

  @Test
  void testRestore_manifestWithNoDeletions() throws Exception {
    auditedOfy().saveWithoutBackup().entity(TestObject.create("previous to keep")).now();
    Key<CommitLogBucket> bucketKey = getBucketKey(1);
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(bucketKey, now);
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs =
        saveDiffFile(
            gcsUtils,
            createCheckpoint(now),
            CommitLogManifest.create(bucketKey, now, null),
            CommitLogMutation.create(manifestKey, TestObject.create("a")),
            CommitLogMutation.create(manifestKey, TestObject.create("b")));
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep", "a", "b");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  @Test
  void testRestore_manifestWithNoMutations() throws Exception {
    auditedOfy()
        .saveWithoutBackup()
        .entities(TestObject.create("previous to keep"), TestObject.create("previous to delete"))
        .now();
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs =
        saveDiffFile(
            gcsUtils,
            createCheckpoint(now),
            CommitLogManifest.create(
                getBucketKey(1),
                now,
                ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  // This is a pathological case that shouldn't be possible, but we should be robust to it.
  @Test
  void testRestore_manifestWithNoMutationsOrDeletions() throws Exception {
    auditedOfy().saveWithoutBackup().entities(TestObject.create("previous to keep")).now();
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs =
        saveDiffFile(
            gcsUtils, createCheckpoint(now), CommitLogManifest.create(getBucketKey(1), now, null));
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  @Test
  void testRestore_mutateExistingEntity() throws Exception {
    auditedOfy().saveWithoutBackup().entity(TestObject.create("existing", "a")).now();
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(getBucketKey(1), now);
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs =
        saveDiffFile(
            gcsUtils,
            createCheckpoint(now),
            CommitLogManifest.create(getBucketKey(1), now, null),
            CommitLogMutation.create(manifestKey, TestObject.create("existing", "b")));
    action.run();
    auditedOfy().clearSessionCache();
    assertThat(auditedOfy().load().entity(TestObject.create("existing")).now().getField())
        .isEqualTo("b");
    assertInDatastore(commitLogs);
    assertInDatastore(CommitLogCheckpointRoot.create(now));
    assertCommitLogBuckets(ImmutableMap.of(1, now));
  }

  // This should be harmless; deletes are idempotent.
  @Test
  void testRestore_deleteMissingEntity() throws Exception {
    auditedOfy().saveWithoutBackup().entity(TestObject.create("previous to keep", "a")).now();
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    Iterable<ImmutableObject> commitLogs =
        saveDiffFile(
            gcsUtils,
            createCheckpoint(now),
            CommitLogManifest.create(
                getBucketKey(1),
                now,
                ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    action.run();
    auditedOfy().clearSessionCache();
    assertExpectedIds("previous to keep");
    assertInDatastore(commitLogs);
    assertCommitLogBuckets(ImmutableMap.of(1, now));
    assertInDatastore(CommitLogCheckpointRoot.create(now));
  }

  @Test
  void testRestore_fromOtherProject() throws IOException {
    // Input resource is a standard commit log file whose entities has "AppId_1" as appId. Among the
    // entities are CommitLogMutations that have an embedded DomainBase and a ContactResource, both
    // having "AppId_1" as appId. This test verifies that the embedded entities are properly
    // imported, in particular, the domain's 'registrant' key can be used by Objectify to load the
    // contact.
    saveDiffFile(
        gcsUtils,
        Resources.toByteArray(Resources.getResource("google/registry/backup/commitlog.data")),
        now);
    action.run();
    auditedOfy().clearSessionCache();
    List<DomainBase> domainBases = auditedOfy().load().type(DomainBase.class).list();
    assertThat(domainBases).hasSize(1);
    DomainBase domainBase = domainBases.get(0);
    // If the registrant is found, then the key instance in domainBase is fixed.
    assertThat(auditedOfy().load().key(domainBase.getRegistrant().getOfyKey()).now()).isNotNull();
  }

  static CommitLogCheckpoint createCheckpoint(DateTime now) {
    return CommitLogCheckpoint.create(now, toMap(getBucketIds(), x -> now));
  }

  static void saveDiffFile(GcsUtils gcsUtils, byte[] rawBytes, DateTime timestamp)
      throws IOException {
    BlobInfo blobInfo =
        BlobInfo.newBuilder(BlobId.of(GCS_BUCKET, DIFF_FILE_PREFIX + timestamp))
            .setMetadata(
                ImmutableMap.of(LOWER_BOUND_CHECKPOINT, timestamp.minusMinutes(1).toString()))
            .build();
    gcsUtils.createFromBytes(blobInfo, rawBytes);
  }

  static Iterable<ImmutableObject> saveDiffFile(
      GcsUtils gcsUtils, CommitLogCheckpoint checkpoint, ImmutableObject... entities)
      throws IOException {
    DateTime now = checkpoint.getCheckpointTime();
    List<ImmutableObject> allEntities = Lists.asList(checkpoint, entities);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (ImmutableObject entity : allEntities) {
      serializeEntity(entity, output);
    }
    saveDiffFile(gcsUtils, output.toByteArray(), now);
    return allEntities;
  }

  static void saveDiffFileNotToRestore(GcsUtils gcsUtils, DateTime now) throws Exception {
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now),
        CommitLogManifest.create(getBucketKey(1), now, null),
        CommitLogMutation.create(
            CommitLogManifest.createKey(getBucketKey(1), now),
            TestObject.create("should not be restored")));
  }

  private void assertExpectedIds(String... ids) {
    assertThat(transform(auditedOfy().load().type(TestObject.class), TestObject::getId))
        .containsExactly((Object[]) ids);
  }

  private void assertInDatastore(ImmutableObject entity) {
    assertThat(auditedOfy().load().entity(entity).now()).isEqualTo(entity);
  }

  private void assertInDatastore(Iterable<? extends ImmutableObject> entities) {
    assertThat(auditedOfy().load().entities(entities).values()).containsExactlyElementsIn(entities);
  }

  private void assertCommitLogBuckets(Map<Integer, DateTime> bucketIdsAndTimestamps) {
    Map<Long, CommitLogBucket> buckets =
        auditedOfy()
            .load()
            .type(CommitLogBucket.class)
            .ids(Longs.asList(Longs.toArray(CommitLogBucket.getBucketIds())));
    assertThat(buckets).hasSize(bucketIdsAndTimestamps.size());
    for (Entry<Integer, DateTime> bucketIdAndTimestamp : bucketIdsAndTimestamps.entrySet()) {
      assertThat(buckets.get((long) bucketIdAndTimestamp.getKey()).getLastWrittenTime())
          .isEqualTo(bucketIdAndTimestamp.getValue());
    }
  }
}
