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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.PENDING_TRANSFER;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentVerifiedRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.time.Duration.standardSeconds;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.UserInfo;
import google.registry.tools.DomainLockUtils;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RelockDomainAction}. */
@ExtendWith(MockitoExtension.class)
public class RelockDomainActionTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String CLIENT_ID = "TheRegistrar";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-05-18T12:34:56Z"));
  private final DomainLockUtils domainLockUtils =
      new DomainLockUtils(
          new DeterministicStringGenerator(Alphabets.BASE_58),
          "adminreg",
          AsyncTaskEnqueuerTest.createForTesting(
              mock(AppEngineServiceUtils.class), clock, Duration.ZERO));

  @RegisterExtension
  public final AppEngineExtension appEngineRule =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withTaskQueue()
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  private DomainBase domain;
  private RegistryLock oldLock;
  @Mock private SendEmailService sendEmailService;
  private AsyncTaskEnqueuer asyncTaskEnqueuer;
  private RelockDomainAction action;

  @BeforeEach
  void beforeEach() throws Exception {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase(DOMAIN_NAME, host));

    oldLock = domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, POC_ID, false);
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
    oldLock =
        domainLockUtils.administrativelyApplyUnlock(
            DOMAIN_NAME, CLIENT_ID, false, Optional.empty());
    assertThat(reloadDomain(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);

    AppEngineServiceUtils appEngineServiceUtils = mock(AppEngineServiceUtils.class);
    lenient()
        .when(appEngineServiceUtils.getServiceHostname("backend"))
        .thenReturn("backend.hostname.fake");

    asyncTaskEnqueuer =
        AsyncTaskEnqueuerTest.createForTesting(appEngineServiceUtils, clock, Duration.ZERO);
    action = createAction(oldLock.getRevisionId());
  }

  @AfterEach
  void afterEach() {
    verifyNoMoreInteractions(sendEmailService);
  }

  @Test
  void testLock() {
    action.run();
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);

    // the old lock should have a reference to the relock
    RegistryLock newLock = getMostRecentVerifiedRegistryLockByRepoId(domain.getRepoId()).get();
    assertThat(getRegistryLockByVerificationCode(oldLock.getVerificationCode()).get().getRelock())
        .isEqualTo(newLock);
  }

  @Test
  void testFailure_unknownCode() throws Exception {
    action = createAction(12128675309L);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Re-lock failed: Unknown revision ID 12128675309");
    assertTaskEnqueued(1, 12128675309L, Duration.standardMinutes(10)); // should retry, transient
  }

  @Test
  void testFailure_pendingDelete() throws Exception {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_DELETE)).build());
    action.run();
    String expectedFailureMessage = "Domain example.tld has a pending delete.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Re-lock failed: %s", expectedFailureMessage));
    assertNonTransientFailureEmail(expectedFailureMessage);
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  void testFailure_pendingTransfer() throws Exception {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_TRANSFER)).build());
    action.run();
    String expectedFailureMessage = "Domain example.tld has a pending transfer.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Re-lock failed: %s", expectedFailureMessage));
    assertNonTransientFailureEmail(expectedFailureMessage);
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  void testFailure_domainAlreadyLocked() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, null, true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Domain example.tld is already manually re-locked, skipping automated re-lock.");
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  void testFailure_domainDeleted() throws Exception {
    persistDomainAsDeleted(domain, clock.nowUtc());
    action.run();
    String expectedFailureMessage = "Domain example.tld has been deleted.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Re-lock failed: %s", expectedFailureMessage));
    assertNonTransientFailureEmail(expectedFailureMessage);
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  void testFailure_domainTransferred() throws Exception {
    persistResource(domain.asBuilder().setPersistedCurrentSponsorClientId("NewRegistrar").build());
    action.run();
    String expectedFailureMessage =
        "Domain example.tld has been transferred from registrar TheRegistrar to registrar "
            + "NewRegistrar since the unlock.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Re-lock failed: %s", expectedFailureMessage));
    assertNonTransientFailureEmail(expectedFailureMessage);
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  public void testFailure_transientFailure_enqueuesTask() {
    // Hard-delete the domain to simulate a DB failure
    deleteResource(domain);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Re-lock failed: null");
    assertTaskEnqueued(1);
  }

  @Test
  void testFailure_sufficientTransientFailures_sendsEmail() throws Exception {
    // Hard-delete the domain to simulate a DB failure
    deleteResource(domain);
    action = createAction(oldLock.getRevisionId(), RelockDomainAction.FAILURES_BEFORE_EMAIL);
    action.run();
    assertTaskEnqueued(RelockDomainAction.FAILURES_BEFORE_EMAIL + 1);
    assertTransientFailureEmail();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Re-lock failed: null");
  }

  @Test
  void testSuccess_afterSufficientFailures_sendsEmail() throws Exception {
    action = createAction(oldLock.getRevisionId(), RelockDomainAction.FAILURES_BEFORE_EMAIL + 1);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertSuccessEmailSent();
  }

  @Test
  void testFailure_relockAlreadySet() {
    RegistryLock newLock =
        domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, null, true);
    saveRegistryLock(oldLock.asBuilder().setRelock(newLock).build());
    // Save the domain without the lock statuses so that we pass that check in the action
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of()).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Domain example.tld is already manually re-locked, skipping automated re-lock.");
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }

  @Test
  void testFailure_slowsDown() throws Exception {
    deleteResource(domain);
    action = createAction(oldLock.getRevisionId(), RelockDomainAction.ATTEMPTS_BEFORE_SLOWDOWN);
    action.run();
    assertTaskEnqueued(
        RelockDomainAction.ATTEMPTS_BEFORE_SLOWDOWN + 1,
        oldLock.getRevisionId(),
        Duration.standardHours(1));
  }

  private void assertSuccessEmailSent() throws Exception {
    EmailMessage expectedEmail =
        EmailMessage.newBuilder()
            .setSubject("Successful re-lock of domain example.tld")
            .setBody(
                "The domain example.tld was successfully re-locked.\n\nPlease "
                    + "contact support at support@example.com if you have any questions.")
            .setRecipients(
                ImmutableSet.of(new InternetAddress("Marla.Singer.RegistryLock@crr.com")))
            .setFrom(new InternetAddress("outgoing@example.com"))
            .build();
    verify(sendEmailService).sendEmail(expectedEmail);
  }

  private void assertNonTransientFailureEmail(String exceptionMessage) throws Exception {
    String expectedBody =
        String.format(
            "There was an error when automatically re-locking example.tld. Error message: %s\n\n"
                + "Please contact support at support@example.com if you have any questions.",
            exceptionMessage);
    assertFailureEmailWithBody(
        expectedBody, ImmutableSet.of(new InternetAddress("Marla.Singer.RegistryLock@crr.com")));
  }

  private void assertTransientFailureEmail() throws Exception {
    String expectedBody =
        "There was an unexpected error when automatically re-locking example.tld. We will continue "
            + "retrying the lock for five hours. Please contact support at support@example.com if "
            + "you have any questions";
    assertFailureEmailWithBody(
        expectedBody,
        ImmutableSet.of(
            new InternetAddress("Marla.Singer.RegistryLock@crr.com"),
            new InternetAddress("alerts@example.com")));
  }

  private void assertFailureEmailWithBody(String body, ImmutableSet<InternetAddress> recipients)
      throws Exception {
    EmailMessage expectedEmail =
        EmailMessage.newBuilder()
            .setSubject("Error re-locking domain example.tld")
            .setBody(body)
            .setRecipients(recipients)
            .setFrom(new InternetAddress("outgoing@example.com"))
            .build();
    verify(sendEmailService).sendEmail(expectedEmail);
  }

  private void assertTaskEnqueued(int numAttempts) {
    assertTaskEnqueued(numAttempts, oldLock.getRevisionId(), Duration.standardMinutes(10));
  }

  private void assertTaskEnqueued(int numAttempts, long oldUnlockRevisionId, Duration duration) {
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(RelockDomainAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(oldUnlockRevisionId))
            .param(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM, String.valueOf(numAttempts))
            .etaDelta(duration.minus(standardSeconds(30)), duration.plus(standardSeconds(30))));
  }

  private DomainBase reloadDomain(DomainBase domain) {
    return ofy().load().entity(domain).now();
  }

  private RelockDomainAction createAction(Long oldUnlockRevisionId) throws Exception {
    return createAction(oldUnlockRevisionId, 0);
  }

  private RelockDomainAction createAction(Long oldUnlockRevisionId, int previousAttempts)
      throws Exception {
    InternetAddress alertRecipientAddress = new InternetAddress("alerts@example.com");
    InternetAddress gSuiteOutgoingAddress = new InternetAddress("outgoing@example.com");
    return new RelockDomainAction(
        oldUnlockRevisionId,
        previousAttempts,
        alertRecipientAddress,
        gSuiteOutgoingAddress,
        "support@example.com",
        sendEmailService,
        domainLockUtils,
        response,
        asyncTaskEnqueuer);
  }
}
