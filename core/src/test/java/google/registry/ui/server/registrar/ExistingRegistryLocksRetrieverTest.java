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
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.testing.AppEngineRule.makeRegistrarContact3;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import java.util.UUID;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ExistingRegistryLocksRetriever}. */
@RunWith(JUnit4.class)
public final class ExistingRegistryLocksRetrieverTest {

  @Rule public final AppEngineRule appEngineRule = AppEngineRule.builder().withDatastore().build();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  private final User user = new User("Marla.Singer@crr.com", "gmail.com", "12345");

  private AuthResult authResult;
  private AuthenticatedRegistrarAccessor accessor;
  private ExistingRegistryLocksRetriever retriever;

  @Before
  public void setup() {
    jpaTmRule.getTxnClock().setTo(DateTime.parse("2000-06-08T22:00:00.0Z"));
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    accessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "TheRegistrar", OWNER,
                "NewRegistrar", OWNER));
    retriever = new ExistingRegistryLocksRetriever(accessor, authResult);
  }

  @Test
  public void testSuccess_returnsLocks() throws Exception {
    RegistryLock regularLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("example.test")
            .setRegistrarId("TheRegistrar")
            .setAction(Action.LOCK)
            .setVerificationCode(UUID.randomUUID().toString())
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setCompletionTimestamp(jpaTmRule.getTxnClock().nowUtc())
            .build();
    jpaTmRule.getTxnClock().advanceOneMilli();
    RegistryLock adminLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("adminexample.test")
            .setRegistrarId("TheRegistrar")
            .setAction(Action.LOCK)
            .setVerificationCode(UUID.randomUUID().toString())
            .isSuperuser(true)
            .setCompletionTimestamp(jpaTmRule.getTxnClock().nowUtc())
            .build();
    RegistryLock incompleteLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("incomplete.test")
            .setRegistrarId("TheRegistrar")
            .setAction(Action.LOCK)
            .setVerificationCode(UUID.randomUUID().toString())
            .setRegistrarPocId("johndoe@theregistrar.com")
            .build();

    RegistryLockDao.save(regularLock);
    RegistryLockDao.save(adminLock);
    RegistryLockDao.save(incompleteLock);

    ImmutableMap<String, Object> result = retriever.getLockedDomainsMap("TheRegistrar");
    assertThat(result)
        .containsExactly(
            "lockEnabledForContact",
            true,
            "email",
            "Marla.Singer@crr.com",
            "clientId",
            "TheRegistrar",
            "locks",
            ImmutableList.of(
                ImmutableMap.of(
                    "fullyQualifiedDomainName", "example.test",
                    "lockedTime", "2000-06-08T22:00:00.000Z",
                    "lockedBy", "johndoe@theregistrar.com"),
                ImmutableMap.of(
                    "fullyQualifiedDomainName", "adminexample.test",
                    "lockedTime", "2000-06-08T22:00:00.001Z",
                    "lockedBy", "admin")));
  }

  @Test
  public void testSuccess_lockAllowedWhenEnabled() throws Exception {
    ImmutableMap<String, Object> result = retriever.getLockedDomainsMap("TheRegistrar");
    assertThat(result)
        .containsExactly(
            "lockEnabledForContact",
            true,
            "email",
            "Marla.Singer@crr.com",
            "clientId",
            "TheRegistrar",
            "locks",
            ImmutableList.of());
  }

  @Test
  public void testSuccess_readOnlyAccessForOtherUsers() throws Exception {
    // If lock is not enabled for a user, this should be read-only
    persistResource(
        makeRegistrarContact3().asBuilder().setAllowedToSetRegistryLockPassword(true).build());
    ImmutableMap<String, Object> result = retriever.getLockedDomainsMap("TheRegistrar");
    assertThat(result)
        .containsExactly(
            "lockEnabledForContact",
            false,
            "email",
            "Marla.Singer@crr.com",
            "clientId",
            "TheRegistrar",
            "locks",
            ImmutableList.of());
  }

  @Test
  public void testSuccess_lockAllowedForAdmin() throws Exception {
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, true));
    retriever = new ExistingRegistryLocksRetriever(accessor, authResult);
    ImmutableMap<String, Object> result = retriever.getLockedDomainsMap("TheRegistrar");
    assertThat(result)
        .containsExactly(
            "lockEnabledForContact",
            true,
            "email",
            "Marla.Singer@crr.com",
            "clientId",
            "TheRegistrar",
            "locks",
            ImmutableList.of());
  }

  @Test
  public void testFailure_lockNotAllowedForRegistrar() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> retriever.getLockedDomainsMap("NewRegistrar")))
        .hasMessageThat()
        .isEqualTo("Registry lock not allowed for this registrar");
  }

  @Test
  public void testFailure_accessDenied() {
    accessor = AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    retriever = new ExistingRegistryLocksRetriever(accessor, authResult);
    assertThat(
            assertThrows(
                RegistrarAccessDeniedException.class,
                () -> retriever.getLockedDomainsMap("TheRegistrar")))
        .hasMessageThat()
        .isEqualTo("TestUserId doesn't have access to registrar TheRegistrar");
  }

  @Test
  public void testFailure_badRegistrar() {
    assertThat(
            assertThrows(
                RegistrarAccessDeniedException.class,
                () -> retriever.getLockedDomainsMap("NonexistentRegistrar")))
        .hasMessageThat()
        .isEqualTo("Registrar NonexistentRegistrar does not exist");
  }

  @Test
  public void testFailure_noAuthInfo() {
    retriever = new ExistingRegistryLocksRetriever(accessor, AuthResult.NOT_AUTHENTICATED);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> retriever.getLockedDomainsMap("TheRegistrar")))
        .hasMessageThat()
        .isEqualTo("User auth info must be present");
  }
}
