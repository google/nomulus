// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.backup.RestoreCommitLogsActionTest.createCheckpoint;
import static google.registry.backup.RestoreCommitLogsActionTest.saveDiffFile;
import static google.registry.backup.RestoreCommitLogsActionTest.saveDiffFileNotToRestore;
import static google.registry.model.common.DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.truth.Truth8;
import com.google.common.util.concurrent.MoreExecutors;
import com.googlecode.objectify.Key;
import google.registry.gcs.GcsUtils;
import google.registry.gcs.backport.LocalStorageHelper;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.server.Lock;
import google.registry.model.tmch.ClaimsList;
import google.registry.model.translators.VKeyTranslatorFactory;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.schema.replay.SqlReplayCheckpoint;
import google.registry.schema.tld.PremiumEntry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.TestObject;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.util.concurrent.Executors;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link ReplayCommitLogsToSqlAction}. */
@ExtendWith(MockitoExtension.class)
public class ReplayCommitLogsToSqlActionTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(fakeClock)
          .withOfyTestEntities(TestObject.class)
          .withJpaUnitTestEntities(
              ContactResource.class,
              DelegationSignerData.class,
              DomainBase.class,
              GracePeriod.class,
              PremiumList.class,
              PremiumEntry.class,
              RegistrarContact.class,
              SqlReplayCheckpoint.class,
              TestObject.class)
          .build();

  /** Local GCS service. */
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  private final ReplayCommitLogsToSqlAction action = new ReplayCommitLogsToSqlAction();
  private final FakeResponse response = new FakeResponse();
  @Mock private RequestStatusChecker requestStatusChecker;

  @BeforeAll
  static void beforeAll() {
    VKeyTranslatorFactory.addTestEntityClass(TestObject.class);
  }

  @BeforeEach
  void beforeEach() {
    action.gcsUtils = gcsUtils;
    action.response = response;
    action.requestStatusChecker = requestStatusChecker;
    action.clock = fakeClock;
    action.gcsBucket = "gcs bucket";
    action.diffLister = new GcsDiffFileLister();
    action.diffLister.gcsUtils = gcsUtils;
    action.diffLister.lazyExecutor = MoreExecutors::newDirectExecutorService;
    action.diffLister.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    ofyTm()
        .transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    ImmutableSortedMap.of(
                        START_OF_TIME,
                        MigrationState.DATASTORE_ONLY,
                        START_OF_TIME.plusMinutes(1),
                        MigrationState.DATASTORE_PRIMARY)));
    TestObject.beforeSqlSaveCallCount = 0;
    TestObject.beforeSqlDeleteCallCount = 0;
  }

  @AfterEach
  void afterEach() {
    ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(DEFAULT_TRANSITION_MAP.toValueMap()));
  }

  @Test
  void testReplay_multipleDiffFiles() throws Exception {
    jpaTm()
        .transact(
            () -> {
              jpaTm().insertWithoutBackup(TestObject.create("previous to keep"));
              jpaTm().insertWithoutBackup(TestObject.create("previous to delete"));
            });
    DateTime now = fakeClock.nowUtc();
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
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now),
        CommitLogManifest.create(
            getBucketKey(1),
            now.minusMinutes(1),
            ImmutableSet.of(Key.create(TestObject.create("c")))),
        CommitLogMutation.create(manifest2Key, TestObject.create("e")),
        CommitLogMutation.create(manifest2Key, TestObject.create("f")));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    fakeClock.advanceOneMilli();
    runAndAssertSuccess(now);
    assertExpectedIds("previous to keep", "b", "d", "e", "f");
  }

  @Test
  void testReplay_noManifests() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> jpaTm().insertWithoutBackup(TestObject.create("previous to keep")));
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1));
    saveDiffFile(gcsUtils, createCheckpoint(now.minusMillis(2)));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMillis(1)));
    runAndAssertSuccess(now.minusMillis(1));
    assertExpectedIds("previous to keep");
  }

  @Test
  void testReplay_dryRun() throws Exception {
    action.dryRun = true;
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> jpaTm().insertWithoutBackup(TestObject.create("previous to keep")));
    Key<CommitLogBucket> bucketKey = getBucketKey(1);
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(bucketKey, now);
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(2));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(bucketKey, now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("a")),
        CommitLogMutation.create(manifestKey, TestObject.create("b")));

    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "Running in dry-run mode; would have processed %d files. They are (limit 10):\n"
                + "commit_diff_until_1999-12-31T23:59:00.000Z");
  }

  @Test
  void testReplay_manifestWithNoDeletions() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> jpaTm().insertWithoutBackup(TestObject.create("previous to keep")));
    Key<CommitLogBucket> bucketKey = getBucketKey(1);
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(bucketKey, now);
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(2));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(bucketKey, now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("a")),
        CommitLogMutation.create(manifestKey, TestObject.create("b")));
    runAndAssertSuccess(now.minusMinutes(1));
    assertExpectedIds("previous to keep", "a", "b");
  }

  @Test
  void testReplay_manifestWithNoMutations() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm()
        .transact(
            () -> {
              jpaTm().insertWithoutBackup(TestObject.create("previous to keep"));
              jpaTm().insertWithoutBackup(TestObject.create("previous to delete"));
            });
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(2));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(
            getBucketKey(1),
            now,
            ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    runAndAssertSuccess(now.minusMinutes(1));
    assertExpectedIds("previous to keep");
  }

  @Test
  void wtestReplay_mutateExistingEntity() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> jpaTm().put(TestObject.create("existing", "a")));
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(getBucketKey(1), now);
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1).minusMillis(1));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMillis(1)),
        CommitLogManifest.create(getBucketKey(1), now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("existing", "b")));
    action.run();
    TestObject fromDatabase =
        jpaTm().transact(() -> jpaTm().loadByKey(VKey.createSql(TestObject.class, "existing")));
    assertThat(fromDatabase.getField()).isEqualTo("b");
  }

  // This should be harmless
  @Test
  void testReplay_deleteMissingEntity() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> jpaTm().put(TestObject.create("previous to keep", "a")));
    saveDiffFileNotToRestore(gcsUtils, now.minusMinutes(1).minusMillis(1));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMillis(1)),
        CommitLogManifest.create(
            getBucketKey(1),
            now,
            ImmutableSet.of(Key.create(TestObject.create("previous to delete")))));
    action.run();
    assertExpectedIds("previous to keep");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void testReplay_properlyWeighted() throws Exception {
    DateTime now = fakeClock.nowUtc();
    Key<CommitLogManifest> manifestKey =
        CommitLogManifest.createKey(getBucketKey(1), now.minusMinutes(1));
    // Create (but don't save to SQL) a domain + contact
    createTld("tld");
    DomainBase domain = newDomainBase("example.tld");
    CommitLogMutation domainMutation =
        tm().transact(() -> CommitLogMutation.create(manifestKey, domain));
    ContactResource contact = tm().transact(() -> tm().loadByKey(domain.getRegistrant()));
    CommitLogMutation contactMutation =
        tm().transact(() -> CommitLogMutation.create(manifestKey, contact));

    // Create and save to SQL a registrar contact that we will delete
    RegistrarContact toDelete = AppEngineExtension.makeRegistrarContact1();
    jpaTm().transact(() -> jpaTm().put(toDelete));
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));

    // spy the txn manager so we can see what order things were inserted/removed
    JpaTransactionManager spy = spy(jpaTm());
    TransactionManagerFactory.setJpaTm(() -> spy);
    // Save in the commit logs the domain and contact (in that order) and the token deletion
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(
            getBucketKey(1), now.minusMinutes(1), ImmutableSet.of(Key.create(toDelete))),
        domainMutation,
        contactMutation);

    runAndAssertSuccess(now.minusMinutes(1));
    // Verify two things:
    // 1. that the contact insert occurred before the domain insert (necessary for FK ordering)
    //    even though the domain came first in the file
    // 2. that the allocation token delete occurred after the insertions
    InOrder inOrder = Mockito.inOrder(spy);
    inOrder.verify(spy).put(any(ContactResource.class));
    inOrder.verify(spy).put(any(DomainBase.class));
    inOrder.verify(spy).delete(toDelete.createVKey());
    inOrder.verify(spy).put(any(SqlReplayCheckpoint.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void testReplay_properlyWeighted_doesNotApplyCrossTransactions() throws Exception {
    DateTime now = fakeClock.nowUtc();
    Key<CommitLogManifest> manifestKey =
        CommitLogManifest.createKey(getBucketKey(1), now.minusMinutes(1));

    // Create and save the standard contact
    ContactResource contact = persistActiveContact("contact1234");
    jpaTm().transact(() -> jpaTm().put(contact));

    // Simulate a Datastore transaction with a new version of the contact
    ContactResource contactWithEdit =
        contact.asBuilder().setEmailAddress("replay@example.tld").build();
    CommitLogMutation contactMutation =
        ofyTm().transact(() -> CommitLogMutation.create(manifestKey, contactWithEdit));

    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));

    // spy the txn manager so we can see what order things were inserted
    JpaTransactionManager spy = spy(jpaTm());
    TransactionManagerFactory.setJpaTm(() -> spy);
    // Save two commits -- the deletion, then the new version of the contact
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1).plusMillis(1)),
        CommitLogManifest.create(
            getBucketKey(1), now.minusMinutes(1), ImmutableSet.of(Key.create(contact))),
        CommitLogManifest.create(
            getBucketKey(1), now.minusMinutes(1).plusMillis(1), ImmutableSet.of()),
        contactMutation);
    runAndAssertSuccess(now.minusMinutes(1).plusMillis(1));
    // Verify that the delete occurred first (because it was in the first transaction) even though
    // deletes have higher weight
    ArgumentCaptor<Object> putCaptor = ArgumentCaptor.forClass(Object.class);
    InOrder inOrder = Mockito.inOrder(spy);
    inOrder.verify(spy).delete(contact.createVKey());
    inOrder.verify(spy).put(putCaptor.capture());
    assertThat(putCaptor.getValue().getClass()).isEqualTo(ContactResource.class);
    assertThat(jpaTm().transact(() -> jpaTm().loadByKey(contact.createVKey()).getEmailAddress()))
        .isEqualTo("replay@example.tld");
  }

  @Test
  void testSuccess_nonReplicatedEntity_isNotReplayed() {
    DateTime now = fakeClock.nowUtc();

    // spy the txn manager so we can verify it's never called
    JpaTransactionManager spy = spy(jpaTm());
    TransactionManagerFactory.setJpaTm(() -> spy);

    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    Key<CommitLogManifest> manifestKey =
        CommitLogManifest.createKey(getBucketKey(1), now.minusMinutes(1));

    createTld("tld");
    // Have a commit log with a couple objects that shouldn't be replayed
    ReservedList reservedList =
        new ReservedList.Builder().setReservedListMap(ImmutableMap.of()).setName("name").build();
    ForeignKeyIndex<DomainBase> fki = ForeignKeyIndex.create(newDomainBase("foo.tld"), now);
    tm().transact(
            () -> {
              try {
                saveDiffFile(
                    gcsUtils,
                    createCheckpoint(now.minusMinutes(1)),
                    CommitLogManifest.create(
                        getBucketKey(1), now.minusMinutes(1), ImmutableSet.of()),
                    // Reserved list is dually-written non-replicated
                    CommitLogMutation.create(manifestKey, reservedList),
                    // FKIs aren't replayed to SQL at all
                    CommitLogMutation.create(manifestKey, fki));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    runAndAssertSuccess(now.minusMinutes(1));
    // jpaTm()::put should only have been called with the checkpoint
    verify(spy, times(2)).put(any(SqlReplayCheckpoint.class));
    verify(spy, times(2)).put(any());
  }

  @Test
  void testSuccess_nonReplicatedEntity_isNotDeleted() throws Exception {
    DateTime now = fakeClock.nowUtc();
    // spy the txn manager so we can verify it's never called
    JpaTransactionManager spy = spy(jpaTm());
    TransactionManagerFactory.setJpaTm(() -> spy);

    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    // Save a couple deletes that aren't propagated to SQL (the objects deleted are irrelevant)
    Key<ClaimsList> claimsListKey = Key.create(ClaimsList.class, 1L);
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(
            getBucketKey(1),
            now.minusMinutes(1),
            // one object only exists in Datastore, one is dually-written (so isn't replicated)
            ImmutableSet.of(getCrossTldKey(), claimsListKey)));

    runAndAssertSuccess(now.minusMinutes(1));
    verify(spy, times(0)).delete(any(VKey.class));
  }

  @Test
  void testFailure_notEnabled() {
    ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(DEFAULT_TRANSITION_MAP.toValueMap()));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(
            "Skipping ReplayCommitLogsToSqlAction because we are in migration phase"
                + " DATASTORE_ONLY.");
  }

  @Test
  void testFailure_cannotAcquireLock() {
    Truth8.assertThat(
            Lock.acquire(
                ReplayCommitLogsToSqlAction.class.getSimpleName(),
                null,
                Duration.standardHours(1),
                requestStatusChecker,
                false))
        .isPresent();
    fakeClock.advanceOneMilli();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Can't acquire SQL commit log replay lock, aborting.");
  }

  @Test
  void testSuccess_beforeSqlSaveCallback() throws Exception {
    DateTime now = fakeClock.nowUtc();
    Key<CommitLogBucket> bucketKey = getBucketKey(1);
    Key<CommitLogManifest> manifestKey = CommitLogManifest.createKey(bucketKey, now);
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(bucketKey, now, null),
        CommitLogMutation.create(manifestKey, TestObject.create("a")));
    runAndAssertSuccess(now.minusMinutes(1));
    assertThat(TestObject.beforeSqlSaveCallCount).isEqualTo(1);
  }

  @Test
  void testSuccess_deleteSqlCallback() throws Exception {
    DateTime now = fakeClock.nowUtc();
    jpaTm().transact(() -> SqlReplayCheckpoint.set(now.minusMinutes(1).minusMillis(1)));
    saveDiffFile(
        gcsUtils,
        createCheckpoint(now.minusMinutes(1)),
        CommitLogManifest.create(
            getBucketKey(1),
            now.minusMinutes(1),
            ImmutableSet.of(Key.create(TestObject.create("to delete")))));
    action.run();
    assertThat(TestObject.beforeSqlDeleteCallCount).isEqualTo(1);
  }

  private void runAndAssertSuccess(DateTime expectedCheckpointTime) {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo("ReplayCommitLogsToSqlAction completed successfully.");
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(expectedCheckpointTime);
  }

  private void assertExpectedIds(String... expectedIds) {
    ImmutableList<String> actualIds =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(TestObject.class).stream()
                        .map(TestObject::getId)
                        .collect(toImmutableList()));
    assertThat(actualIds).containsExactlyElementsIn(expectedIds);
  }
}
