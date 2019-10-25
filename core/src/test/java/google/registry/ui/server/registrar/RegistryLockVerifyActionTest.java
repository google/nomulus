// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableMap;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import google.registry.testing.UserInfo;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RegistryLockVerifyActionTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create("marla.singer@example.com", "12345"))
          .build();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  @Rule public final InjectRule inject = new InjectRule();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final FakeResponse response = new FakeResponse();
  private final UserService userService = UserServiceFactory.getUserService();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");
  private final String lockId = "f1be78a2-2d61-458c-80f0-9dd8f2f8625f";

  private DomainBase domain;
  private AuthResult authResult;
  private RegistryLockVerifyAction action;

  @Before
  public void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase("example.tld", host));
    when(request.getRequestURI()).thenReturn("https://registry.example/registry-lock-verification");
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action =
        new RegistryLockVerifyAction(
            request,
            response,
            userService,
            authResult,
            jpaTmRule.getTxnClock(),
            ImmutableMap.of(),
            "logoFilename",
            "productName",
            lockId);
  }

  @Test
  public void testSuccess_lockDomain() {
    RegistryLockDao.save(createLock());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(reloadDomain().getStatusValues()).containsExactlyElementsIn(REGISTRY_LOCK_STATUSES);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason()).isEqualTo("lock");
    assertBillingEvent(historyEntry);
  }

  @Test
  public void testSuccess_unlockDomain() {
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLockDao.save(createLock().asBuilder().setAction(Action.UNLOCK).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(reloadDomain().getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason()).isEqualTo("unlock");
    assertBillingEvent(historyEntry);
  }

  @Test
  public void testSuccess_adminLock_createsOnlyHistoryEntry() {
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, true));
    action =
        new RegistryLockVerifyAction(
            request,
            response,
            userService,
            authResult,
            jpaTmRule.getTxnClock(),
            ImmutableMap.of(),
            "logoFilename",
            "productName",
            lockId);
    RegistryLockDao.save(createLock().asBuilder().isSuperuser(true).build());

    action.run();
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isFalse();
    assertThat(historyEntry.getBySuperuser()).isTrue();
    DatastoreHelper.assertNoBillingEvents();
  }

  @Test
  public void testFailure_badVerificationCode() {
    RegistryLockDao.save(
        createLock().asBuilder().setVerificationCode(UUID.randomUUID().toString()).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Unknown verification code");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_alreadyVerified() {
    RegistryLockDao.save(
        createLock().asBuilder().setCompletionTimestamp(jpaTmRule.getTxnClock().nowUtc()).build());
    action.run();
    assertThat(response.getPayload())
        .contains("Failed: This lock / unlock has already been verified");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_expired() {
    RegistryLockDao.save(
        createLock()
            .asBuilder()
            .setCreationTimestamp(
                CreateAutoTimestamp.create(jpaTmRule.getTxnClock().nowUtc().minusHours(2)))
            .build());
    action.run();
    assertThat(response.getPayload())
        .contains("Failed: The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_nonAdmin_verifyingAdminLock() {
    RegistryLockDao.save(createLock().asBuilder().isSuperuser(true).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Non-admin user cannot verify admin lock");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_alreadyUnlocked() {
    RegistryLockDao.save(createLock().asBuilder().setAction(Action.UNLOCK).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain already unlocked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_alreadyLocked() {
    RegistryLockDao.save(createLock());
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action.run();
    assertThat(response.getPayload()).contains("Failed: Domain already locked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_notLoggedIn() {
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders()).containsKey("Location");
    assertNoDomainChanges();
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setDomainName("example.tld")
        .setRegistrarId("TheRegistrar")
        .setRepoId("repoId")
        .setRegistrarPocId("marla.singer@example.com")
        .isSuperuser(false)
        .setAction(Action.LOCK)
        .setVerificationCode(lockId.toString())
        .build();
  }

  private DomainBase reloadDomain() {
    return ofy().load().entity(domain).now();
  }

  private void assertNoDomainChanges() {
    assertThat(reloadDomain()).isEqualTo(domain);
  }

  private void assertBillingEvent(HistoryEntry historyEntry) {
    DatastoreHelper.assertBillingEvents(
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.SERVER_STATUS)
            .setTargetId(domain.getForeignKey())
            .setClientId(domain.getCurrentSponsorClientId())
            .setCost(Registry.get(domain.getTld()).getServerStatusChangeCost())
            .setEventTime(jpaTmRule.getTxnClock().nowUtc())
            .setBillingTime(jpaTmRule.getTxnClock().nowUtc())
            .setParent(historyEntry)
            .build());
  }
}
