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

import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.ReservedDomainsTestingUtils.addReservedListsToTld;
import static google.registry.bsa.ReservedDomainsTestingUtils.createReservedList;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.bsa.persistence.BsaTestingUtils.persistDownloadSchedule;
import static google.registry.bsa.persistence.BsaTestingUtils.persistUnblockableDomain;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomain.Reason;
import google.registry.bsa.persistence.BsaTestingUtils;
import google.registry.gcs.GcsUtils;
import google.registry.groups.GmailClient;
import google.registry.model.domain.Domain;
import google.registry.model.tld.label.ReservationType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaValidateAction}. */
@ExtendWith(MockitoExtension.class)
public class BsaValidateActionTest {

  private static final String DOWNLOAD_JOB_NAME = "job";

  private static final Duration MAX_STALENESS = Duration.standardMinutes(1);

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Mock GmailClient gmailClient;

  @Mock IdnChecker idnChecker;

  @Mock Response response;

  @Mock private InternetAddress emailRecipient;

  @Captor ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);

  private GcsClient gcsClient;

  private BsaValidateAction action;

  @BeforeEach
  void setup() throws Exception {
    gcsClient =
        new GcsClient(new GcsUtils(LocalStorageHelper.getOptions()), "my-bucket", "SHA-256");
    action =
        new BsaValidateAction(
            gcsClient,
            idnChecker,
            new BsaEmailSender(gmailClient, emailRecipient),
            /* transactionBatchSize= */ 500,
            MAX_STALENESS,
            fakeClock,
            response);
    createTld("app");
  }

  static void createBlockList(GcsClient gcsClient, BlockListType blockListType, String content)
      throws Exception {
    BlobId blobId =
        gcsClient.getBlobId(DOWNLOAD_JOB_NAME, GcsClient.getBlockListFileName(blockListType));
    try (var writer = gcsClient.getWriter(blobId)) {
      writer.write(content);
    }
  }

  @Test
  void fetchDownloadedLabels_success() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent =
        """
        domainLabel,orderIDs
        test2,4
        test3,5
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    assertThat(action.fetchDownloadedLabels(DOWNLOAD_JOB_NAME))
        .containsExactly("test1", "test2", "test3");
  }

  @Test
  void fetchDownloadedLabels_withUnsupportedIdn_success() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3,
        """;
    String blockPlusContent =
        """
        domainLabel,orderIDs
        test2,4
        test3,5,
        invalid,6,
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    when(idnChecker.getAllValidIdns(startsWith("test")))
        .thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(idnChecker.getAllValidIdns("invalid")).thenReturn(ImmutableSet.of());
    assertThat(action.fetchDownloadedLabels(DOWNLOAD_JOB_NAME))
        .containsExactly("test1", "test2", "test3");
  }

  @Test
  void fetchPersistedLabels_multipleOfBatchSize_success() {
    BsaTestingUtils.persistBsaLabel("a");
    BsaTestingUtils.persistBsaLabel("b");
    BsaTestingUtils.persistBsaLabel("c");

    assertThat(action.fetchPersistedLabels(1)).containsExactly("a", "b", "c");
  }

  @Test
  void fetchPersistedLabels_notMultipleOfBatchSize_success() {
    BsaTestingUtils.persistBsaLabel("a");
    BsaTestingUtils.persistBsaLabel("b");
    BsaTestingUtils.persistBsaLabel("c");

    assertThat(action.fetchPersistedLabels(2)).containsExactly("a", "b", "c");
  }

  @Test
  void checkBsaLabels_noErrors() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent =
        """
        domainLabel,orderIDs
        test2,4
        test3,5
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    BsaTestingUtils.persistBsaLabel("test1");
    BsaTestingUtils.persistBsaLabel("test2");
    BsaTestingUtils.persistBsaLabel("test3");
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));

    assertThat(action.checkBsaLabels(DOWNLOAD_JOB_NAME)).isEmpty();
  }

  @Test
  void checkBsaLabels_withErrors() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent = """
        domainLabel,orderIDs
        test2,4
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    BsaTestingUtils.persistBsaLabel("test2");
    BsaTestingUtils.persistBsaLabel("test3");
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));

    String allErrors = Joiner.on('\n').join(action.checkBsaLabels(DOWNLOAD_JOB_NAME));

    assertThat(allErrors).contains("Found 1 missing labels in the DB. Examples: [test1]");
    assertThat(allErrors).contains("Found 1 unexpected labels in the DB. Examples: [test3]");
  }

  @Test
  void isStalenessAllowed_newDomain_allowed() {
    persistBsaLabel("label");
    Domain domain = persistActiveDomain("label.app", fakeClock.nowUtc());
    fakeClock.advanceBy(MAX_STALENESS.minus(Duration.standardSeconds(1)));
    assertThat(action.isStalenessAllowed(true, domain.createVKey())).isTrue();
  }

  @Test
  void isStalenessAllowed_newDomain_notAllowed() {
    persistBsaLabel("label");
    Domain domain = persistActiveDomain("label.app", fakeClock.nowUtc());
    fakeClock.advanceBy(MAX_STALENESS);
    assertThat(action.isStalenessAllowed(true, domain.createVKey())).isFalse();
  }

  @Test
  void isStalenessAllowed_deletedDomain_allowed() {
    persistBsaLabel("label");
    Domain domain = persistDeletedDomain("label.app", fakeClock.nowUtc());
    fakeClock.advanceBy(MAX_STALENESS.minus(Duration.standardSeconds(1)));
    assertThat(action.isStalenessAllowed(false, domain.createVKey())).isTrue();
  }

  @Test
  void isStalenessAllowed_deletedDomain_notAllowed() {
    persistBsaLabel("label");
    Domain domain = persistDeletedDomain("label.app", fakeClock.nowUtc());
    fakeClock.advanceBy(MAX_STALENESS);
    assertThat(action.isStalenessAllowed(false, domain.createVKey())).isFalse();
  }

  @Test
  void checkUnblockableDomain_noError() {
    createTld("app");
    persistActiveDomain("label.app");
    persistBsaLabel("label");
    persistUnblockableDomain(UnblockableDomain.of("label", "app", Reason.REGISTERED));
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));

    assertThat(action.checkWronglyReportedUnblockableDomains()).isEmpty();
  }

  @Test
  void checkUnblockableDomain_error() {
    createTld("app");
    persistActiveDomain("label.app");
    persistBsaLabel("label");
    persistUnblockableDomain(UnblockableDomain.of("label", "app", Reason.RESERVED));
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));

    assertThat(action.checkWronglyReportedUnblockableDomains())
        .containsExactly("label.app: should be REGISTERED, found RESERVED");
  }

  @Test
  void checkForMissingReservedUnblockables_success() {
    persistResource(
        createTld("app").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistResource(
        createTld("dev").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("registered-reserved");
    persistBsaLabel("reserved-only");
    persistBsaLabel("reserved-missing");
    persistBsaLabel("invalid-in-app");

    persistUnblockableDomain(UnblockableDomain.of("registered-reserved", "app", Reason.REGISTERED));
    persistUnblockableDomain(UnblockableDomain.of("reserved-only", "app", Reason.RESERVED));
    persistUnblockableDomain(UnblockableDomain.of("invalid-in-app", "dev", Reason.RESERVED));

    createReservedList(
        "rl",
        Stream.of("registered-reserved", "reserved-only", "reserved-missing")
            .collect(toImmutableMap(x -> x, x -> ReservationType.RESERVED_FOR_SPECIFIC_USE)));
    addReservedListsToTld("app", ImmutableList.of("rl"));

    ImmutableList<String> errors = action.checkForMissingReservedUnblockables(fakeClock.nowUtc());
    assertThat(errors)
        .containsExactly("Missing unblockable domain: reserved-missing.app is reserved.");
  }

  @Test
  void checkForMissingReservedUnblockablesInOneTld_success() {
    persistResource(
        createTld("app").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistResource(
        createTld("dev").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("reserved-missing-in-app");
    persistUnblockableDomain(
        UnblockableDomain.of("reserved-missing-in-app", "dev", Reason.REGISTERED));

    createReservedList(
        "rl",
        Stream.of("reserved-missing-in-app")
            .collect(toImmutableMap(x -> x, x -> ReservationType.RESERVED_FOR_SPECIFIC_USE)));
    addReservedListsToTld("app", ImmutableList.of("rl"));
    addReservedListsToTld("dev", ImmutableList.of("rl"));

    ImmutableList<String> errors = action.checkForMissingReservedUnblockables(fakeClock.nowUtc());
    assertThat(errors)
        .containsExactly("Missing unblockable domain: reserved-missing-in-app.app is reserved.");
  }

  @Test
  void checkForMissingReservedUnblockables_unblockedReservedNotReported() {
    persistResource(
        createTld("app").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());

    createReservedList(
        "rl",
        Stream.of("reserved-only")
            .collect(toImmutableMap(x -> x, x -> ReservationType.RESERVED_FOR_SPECIFIC_USE)));
    addReservedListsToTld("app", ImmutableList.of("rl"));

    ImmutableList<String> errors = action.checkForMissingReservedUnblockables(fakeClock.nowUtc());
    assertThat(errors).isEmpty();
  }

  @Test
  void checkForMissingRegisteredUnblockables_success() {
    persistResource(
        createTld("app").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("registered");
    persistBsaLabel("registered-missing");
    persistUnblockableDomain(UnblockableDomain.of("registered", "app", Reason.REGISTERED));
    persistUnblockableDomain(UnblockableDomain.of("registered-missing", "app", Reason.RESERVED));
    persistActiveDomain("registered.app");
    persistActiveDomain("registered-missing.app");

    ImmutableList<String> errors = action.checkForMissingRegisteredUnblockables(fakeClock.nowUtc());
    assertThat(errors)
        .containsExactly(
            "Registered domain registered-missing.app missing or not recorded as REGISTERED");
  }

  @Test
  void notificationSent_abortedByException() {
    action = spy(action);
    RuntimeException throwable = new RuntimeException("Error");
    doThrow(throwable).when(action).validate();
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "BSA validation aborted", getStackTraceAsString(throwable), emailRecipient));
  }

  @Test
  void notificationSent_noDownloads() {
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "BSA validation does not run: block list downloads not found", "", emailRecipient));
  }

  @Test
  void notificationSent_withValidationError() {
    persistDownloadSchedule(DownloadStage.DONE);
    action = spy(action);
    doReturn(ImmutableList.of("Error line 1.", "Error line 2"))
        .when(action)
        .checkBsaLabels(anyString());
    action.run();
    verify(gmailClient, times(1)).sendEmail(emailCaptor.capture());
    EmailMessage message = emailCaptor.getValue();
    assertThat(message.subject()).isEqualTo("BSA validation completed with errors");
    assertThat(message.body()).startsWith("Most recent download is");
    assertThat(message.body())
        .isEqualTo(
            """
                Most recent download is 2023-11-09t020857.880z.

                Error line 1.
                Error line 2""");
  }

  @Test
  void notification_notSent_WhenNoError() {
    persistDownloadSchedule(DownloadStage.DONE);
    action = spy(action);
    doReturn(ImmutableList.of()).when(action).checkBsaLabels(anyString());
    action.run();
    verify(gmailClient, never()).sendEmail(any());
  }
}
