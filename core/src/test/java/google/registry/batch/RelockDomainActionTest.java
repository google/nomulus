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
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.PENDING_TRANSFER;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDomainAsDeleted;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentVerifiedRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
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
import google.registry.testing.UserInfo;
import google.registry.tools.DomainLockUtils;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import javax.mail.internet.InternetAddress;
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
  private final FakeClock clock = new FakeClock();
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
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  private DomainBase domain;
  private RegistryLock oldLock;
  @Mock private SendEmailService sendEmailService;
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
    action = createAction(oldLock.getRevisionId());
  }

  @AfterEach
  void afterEach() {
    verifyNoMoreInteractions(sendEmailService);
  }

  @Test
  void testLock() throws Exception {
    action.run();
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);

    // the old lock should have a reference to the relock
    RegistryLock newLock = getMostRecentVerifiedRegistryLockByRepoId(domain.getRepoId()).get();
    assertThat(getRegistryLockByVerificationCode(oldLock.getVerificationCode()).get().getRelock())
        .isEqualTo(newLock);
    assertSuccessEmailSent();
  }

  @Test
  void testFailure_unknownCode() throws Exception {
    action = createAction(12128675309L);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Relock failed: Unknown revision ID 12128675309");
  }

  @Test
  void testFailure_pendingDelete() throws Exception {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_DELETE)).build());
    action.run();
    String expectedFailureMessage = "Domain example.tld has a pending delete.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: %s", expectedFailureMessage));
    assertFailureEmail(expectedFailureMessage);
  }

  @Test
  void testFailure_pendingTransfer() throws Exception {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_TRANSFER)).build());
    action.run();
    String expectedFailureMessage = "Domain example.tld has a pending transfer.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: %s", expectedFailureMessage));
    assertFailureEmail(expectedFailureMessage);
  }

  @Test
  void testFailure_domainAlreadyLocked() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, null, true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Domain example.tld is already manually relocked, skipping automated relock.");
  }

  @Test
  void testFailure_domainDeleted() throws Exception {
    persistDomainAsDeleted(domain, clock.nowUtc());
    action.run();
    String expectedFailureMessage = "Domain example.tld has been deleted.";
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: %s", expectedFailureMessage));
    assertFailureEmail(expectedFailureMessage);
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
        .isEqualTo(String.format("Relock failed: %s", expectedFailureMessage));
    assertFailureEmail(expectedFailureMessage);
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
        .isEqualTo("Domain example.tld is already manually relocked, skipping automated relock.");
  }

  private void assertSuccessEmailSent() throws Exception {
    EmailMessage expectedEmail =
        EmailMessage.newBuilder()
            .setSubject("Successful re-lock of domain example.tld")
            .setBody(
                "This is a notification that we have successfully re-locked example.tld.\n\nPlease "
                    + "contact support at support@example.com if you have any questions.")
            .setRecipients(
                ImmutableSet.of(new InternetAddress("Marla.Singer.RegistryLock@crr.com")))
            .setFrom(new InternetAddress("outgoing@example.com"))
            .build();
    verify(sendEmailService).sendEmail(expectedEmail);
  }

  private void assertFailureEmail(String exceptionMessage) throws Exception {
    EmailMessage expectedEmail =
        EmailMessage.newBuilder()
            .setSubject("Error re-locking domain example.tld")
            .setBody(
                String.format(
                    "There was an error when automatically relocking example.tld. Error message:"
                        + " %s\n\nPlease contact support at support@example.com if you have"
                        + " any questions.",
                    exceptionMessage))
            .setRecipients(
                ImmutableSet.of(
                    new InternetAddress("my-recipient@example.com"),
                    new InternetAddress("Marla.Singer.RegistryLock@crr.com")))
            .setFrom(new InternetAddress("outgoing@example.com"))
            .build();
    verify(sendEmailService).sendEmail(expectedEmail);
  }

  private DomainBase reloadDomain(DomainBase domain) {
    return ofy().load().entity(domain).now();
  }

  private RelockDomainAction createAction(Long oldUnlockRevisionId) throws Exception {
    InternetAddress alertRecipientAddress = new InternetAddress("my-recipient@example.com");
    InternetAddress gSuiteOutgoingAddress = new InternetAddress("outgoing@example.com");
    return new RelockDomainAction(
        oldUnlockRevisionId,
        alertRecipientAddress,
        gSuiteOutgoingAddress,
        "support@example.com",
        sendEmailService,
        domainLockUtils,
        response);
  }
}
