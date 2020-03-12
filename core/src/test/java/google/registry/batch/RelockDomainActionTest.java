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

import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import google.registry.tools.DomainLockUtils;
import google.registry.util.StringGenerator.Alphabets;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RelockDomainAction}. */
@RunWith(JUnit4.class)
public class RelockDomainActionTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String CLIENT_ID = "TheRegistrar";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock();
  private final DomainLockUtils domainLockUtils =
      new DomainLockUtils(new DeterministicStringGenerator(Alphabets.BASE_58));

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastoreAndCloudSql()
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(clock).buildIntegrationWithCoverageRule();

  private DomainBase domain;
  private RegistryLock oldLock;
  private RelockDomainAction action;

  @Before
  public void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase(DOMAIN_NAME, host));

    oldLock = domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, POC_ID, false);
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
    oldLock = domainLockUtils.administrativelyApplyUnlock(DOMAIN_NAME, CLIENT_ID, false);
    assertThat(reloadDomain(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    action = createAction(oldLock.getRevisionId());
  }

  @Test
  public void testLock() {
    action.run();
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);

    // the old lock should have a reference to the relock
    RegistryLock newLock = getMostRecentVerifiedRegistryLockByRepoId(domain.getRepoId()).get();
    assertThat(getRegistryLockByVerificationCode(oldLock.getVerificationCode()).get().getRelock())
        .isEqualTo(newLock);
  }

  @Test
  public void testFailure_unknownCode() {
    action = createAction(12128675309L);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Relock failed: Unknown revision ID 12128675309");
  }

  @Test
  public void testFailure_pendingDelete() {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_DELETE)).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: Domain %s has a pending delete", DOMAIN_NAME));
  }

  @Test
  public void testFailure_pendingTransfer() {
    persistResource(domain.asBuilder().setStatusValues(ImmutableSet.of(PENDING_TRANSFER)).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: Domain %s has a pending transfer", DOMAIN_NAME));
  }

  @Test
  public void testFailure_domainAlreadyLocked() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, CLIENT_ID, null, true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Domain example.tld is already manually relocked, skipping automated relock.");
  }

  @Test
  public void testFailure_domainDeleted() {
    persistDomainAsDeleted(domain, clock.nowUtc());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(String.format("Relock failed: Domain %s has been deleted", DOMAIN_NAME));
  }

  @Test
  public void testFailure_domainTransferred() {
    persistResource(domain.asBuilder().setPersistedCurrentSponsorClientId("NewRegistrar").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(
            String.format(
                "Relock failed: Domain %s has been transferred from registrar %s to registrar "
                    + "%s since the unlock",
                DOMAIN_NAME, CLIENT_ID, "NewRegistrar"));
  }

  @Test
  public void testFailure_relockAlreadySet() {
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

  private DomainBase reloadDomain(DomainBase domain) {
    return ofy().load().entity(domain).now();
  }

  private RelockDomainAction createAction(Long oldUnlockRevisionId) {
    return new RelockDomainAction(oldUnlockRevisionId, domainLockUtils, response);
  }
}
