// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.ReservedDomainsTestingUtils.addReservedDomainToList;
import static google.registry.bsa.ReservedDomainsTestingUtils.addReservedListsToTld;
import static google.registry.bsa.ReservedDomainsTestingUtils.createReservedList;
import static google.registry.bsa.ReservedDomainsTestingUtils.removeReservedDomainFromList;
import static google.registry.bsa.persistence.BsaTestingUtils.createDownloadScheduler;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.bsa.persistence.BsaTestingUtils.queryUnblockableDomains;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.model.tld.label.ReservationType.RESERVED_FOR_SPECIFIC_USE;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.deleteTestDomain;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.api.BsaReportSender;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomain.Reason;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.bsa.persistence.BsaTestingUtils;
import google.registry.gcs.GcsUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Functional tests for refreshing the unblockable domains with recent registration and reservation
 * changes.
 */
@ExtendWith(MockitoExtension.class)
class BsaRefreshFunctionalTest {

  static final DateTime TEST_START_TIME = DateTime.parse("2024-01-01T00:00:00Z");

  static final String RESERVED_LIST_NAME = "reserved";

  private final FakeClock fakeClock = new FakeClock(TEST_START_TIME);

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Mock BsaReportSender bsaReportSender;

  @Mock BsaEmailSender emailSender;

  private GcsClient gcsClient;
  private Response response;
  private BsaRefreshAction action;

  @BeforeEach
  void setup() throws Exception {
    gcsClient =
        new GcsClient(new GcsUtils(LocalStorageHelper.getOptions()), "my-bucket", "SHA-256");
    response = new FakeResponse();
    action =
        new BsaRefreshAction(
            BsaTestingUtils.createRefreshScheduler(),
            gcsClient,
            bsaReportSender,
            /* transactionBatchSize= */ 5,
            /* domainCreateTxnCommitTimeLag= */ Duration.millis(1),
            emailSender,
            new BsaLock(
                new FakeLockHandler(/* lockSucceeds= */ true), Duration.standardSeconds(30)),
            fakeClock,
            response);

    initDb();
  }

  private String getRefreshJobName(DateTime jobStartTime) {
    return jobStartTime.toString() + "-refresh";
  }

  private void initDb() {
    createTlds("app", "dev");
    getTldEntitiesOfType(TldType.REAL)
        .forEach(
            tld ->
                persistResource(
                    tld.asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build()));

    createReservedList(RESERVED_LIST_NAME, "dummy", RESERVED_FOR_SPECIFIC_USE);
    addReservedListsToTld("app", ImmutableList.of(RESERVED_LIST_NAME));

    persistBsaLabel("blocked1");
    persistBsaLabel("blocked2");
    // Creates a download record so that refresher will not quit immediately.
    createDownloadScheduler(fakeClock).schedule().get().updateJobStage(DownloadStage.DONE);
    fakeClock.advanceOneMilli();
  }

  @Test
  void newReservedDomain_addedAsUnblockable() throws Exception {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    UnblockableDomain newUnblockable = new UnblockableDomain("blocked1.app", Reason.RESERVED);
    assertThat(queryUnblockableDomains()).containsExactly(newUnblockable);
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(UnblockableDomainChange.createNew(newUnblockable));
    verify(bsaReportSender, never()).removeUnblockableDomainsUpdates(anyString());
    verify(bsaReportSender, times(1))
        .addUnblockableDomainsUpdates("{\n  \"reserved\": [\n    \"blocked1.app\"\n  ]\n}");

    verify(emailSender, never()).sendNotification(anyString(), anyString());
  }

  @Test
  void newRegisteredDomain_addedAsUnblockable() throws Exception {
    persistActiveDomain("blocked1.dev", fakeClock.nowUtc());
    persistActiveDomain("dummy.dev", fakeClock.nowUtc());
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    UnblockableDomain newUnblockable = new UnblockableDomain("blocked1.dev", Reason.REGISTERED);
    assertThat(queryUnblockableDomains()).containsExactly(newUnblockable);
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(UnblockableDomainChange.createNew(newUnblockable));

    verify(bsaReportSender, never()).removeUnblockableDomainsUpdates(anyString());
    verify(bsaReportSender, times(1))
        .addUnblockableDomainsUpdates("{\n  \"registered\": [\n    \"blocked1.dev\"\n  ]\n}");
  }

  @Test
  void registeredUnblockable_unregistered() {
    Domain domain = persistActiveDomain("blocked1.dev", fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.dev", Reason.REGISTERED));
    fakeClock.advanceOneMilli();
    deleteTestDomain(domain, fakeClock.nowUtc());
    fakeClock.advanceOneMilli();

    String jobName = getRefreshJobName(fakeClock.nowUtc());
    Mockito.reset(bsaReportSender);
    action.run();
    assertThat(queryUnblockableDomains()).isEmpty();
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(
            UnblockableDomainChange.createDeleted(
                new UnblockableDomain("blocked1.dev", Reason.REGISTERED)));

    verify(bsaReportSender, never()).addUnblockableDomainsUpdates(anyString());
    verify(bsaReportSender, times(1)).removeUnblockableDomainsUpdates("[\n  \"blocked1.dev\"\n]");
  }

  @Test
  void reservedUnblockable_noLongerReserved() {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.RESERVED));
    fakeClock.advanceOneMilli();
    removeReservedDomainFromList(RESERVED_LIST_NAME, ImmutableSet.of("blocked1"));

    String jobName = getRefreshJobName(fakeClock.nowUtc());
    Mockito.reset(bsaReportSender);
    action.run();
    assertThat(queryUnblockableDomains()).isEmpty();
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(
            UnblockableDomainChange.createDeleted(
                new UnblockableDomain("blocked1.app", Reason.RESERVED)));

    verify(bsaReportSender, never()).addUnblockableDomainsUpdates(anyString());
    verify(bsaReportSender, times(1)).removeUnblockableDomainsUpdates("[\n  \"blocked1.app\"\n]");
  }

  @Test
  void registeredAndReservedUnblockable_noLongerRegistered_stillUnblockable() throws Exception {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    Domain domain = persistActiveDomain("blocked1.app", fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.REGISTERED));
    fakeClock.advanceOneMilli();
    deleteTestDomain(domain, fakeClock.nowUtc());
    fakeClock.advanceOneMilli();

    String jobName = getRefreshJobName(fakeClock.nowUtc());
    Mockito.reset(bsaReportSender);
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.RESERVED));
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(
            UnblockableDomainChange.createChanged(
                new UnblockableDomain("blocked1.app", Reason.REGISTERED), Reason.RESERVED));
    InOrder inOrder = Mockito.inOrder(bsaReportSender);
    inOrder.verify(bsaReportSender).removeUnblockableDomainsUpdates("[\n  \"blocked1.app\"\n]");
    inOrder
        .verify(bsaReportSender)
        .addUnblockableDomainsUpdates("{\n  \"reserved\": [\n    \"blocked1.app\"\n  ]\n}");
  }

  @Test
  void reservedUblockable_becomesRegistered_changeToRegisterd() throws Exception {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.RESERVED));
    fakeClock.advanceOneMilli();
    persistActiveDomain("blocked1.app", fakeClock.nowUtc());
    fakeClock.advanceOneMilli();

    Mockito.reset(bsaReportSender);
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    UnblockableDomain changed = new UnblockableDomain("blocked1.app", Reason.REGISTERED);
    assertThat(queryUnblockableDomains()).containsExactly(changed);
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(
            UnblockableDomainChange.createChanged(
                new UnblockableDomain("blocked1.app", Reason.RESERVED), Reason.REGISTERED));
    InOrder inOrder = Mockito.inOrder(bsaReportSender);
    inOrder.verify(bsaReportSender).removeUnblockableDomainsUpdates("[\n  \"blocked1.app\"\n]");
    inOrder
        .verify(bsaReportSender)
        .addUnblockableDomainsUpdates("{\n  \"registered\": [\n    \"blocked1.app\"\n  ]\n}");
  }

  @Test
  void newRegisteredAndReservedDomain_addedAsRegisteredUnblockable() throws Exception {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    persistActiveDomain("blocked1.app", fakeClock.nowUtc());
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    UnblockableDomain newUnblockable = new UnblockableDomain("blocked1.app", Reason.REGISTERED);
    assertThat(queryUnblockableDomains()).containsExactly(newUnblockable);
    assertThat(gcsClient.readRefreshChanges(jobName))
        .containsExactly(UnblockableDomainChange.createNew(newUnblockable));
  }

  @Test
  void registeredAndReservedUnblockable_noLongerReserved_noChange() throws Exception {
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    persistActiveDomain("blocked1.app", fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.REGISTERED));
    fakeClock.advanceOneMilli();
    removeReservedDomainFromList(RESERVED_LIST_NAME, ImmutableSet.of("blocked1"));
    fakeClock.advanceOneMilli();

    Mockito.reset(bsaReportSender);
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.REGISTERED));
    // Verify that refresh change file does not exist (404 error) since there is no change.
    assertThat(
            assertThrows(
                UncheckedIOException.class, () -> gcsClient.readRefreshChanges(jobName).findAny()))
        .hasMessageThat()
        .contains("404");
    verifyNoInteractions(bsaReportSender);
  }

  @Test
  void registeredUblockable_becomesReserved_noChange() throws Exception {
    persistActiveDomain("blocked1.app", fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.REGISTERED));
    fakeClock.advanceOneMilli();
    addReservedDomainToList(
        RESERVED_LIST_NAME, ImmutableMap.of("blocked1", RESERVED_FOR_SPECIFIC_USE));
    fakeClock.advanceOneMilli();

    Mockito.reset(bsaReportSender);
    String jobName = getRefreshJobName(fakeClock.nowUtc());
    action.run();
    assertThat(queryUnblockableDomains())
        .containsExactly(new UnblockableDomain("blocked1.app", Reason.REGISTERED));
    // Verify that refresh change file does not exist (404 error) since there is no change.
    assertThat(
            assertThrows(
                UncheckedIOException.class, () -> gcsClient.readRefreshChanges(jobName).findAny()))
        .hasMessageThat()
        .contains("404");
    verifyNoInteractions(bsaReportSender);
  }
}
