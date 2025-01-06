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

package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.domain.RegistryLock;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.DomainLockUtils;
import google.registry.util.StringGenerator;
import jakarta.servlet.http.HttpServletResponse;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link ConsoleRegistryLockVerifyAction}. */
public class ConsoleRegistryLockVerifyActionTest {

  private static final String DEFAULT_CODE = "123456789ABCDEFGHJKLMNPQRSTUUUUU";
  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  private FakeResponse response;
  private Domain defaultDomain;
  private User user;

  private ConsoleRegistryLockVerifyAction action;

  @BeforeEach
  void beforeEach() {
    createTld("test");
    defaultDomain = persistActiveDomain("example.test");
    user =
        new User.Builder()
            .setEmailAddress("user@theregistrar.com")
            .setRegistryLockEmailAddress("registrylock@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .setRegistryLockPassword("registryLockPassword")
            .build();
    action = createAction(DEFAULT_CODE);
  }

  @Test
  void testSuccess_lock() {
    saveRegistryLock(createDefaultLockBuilder().build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "{\"action\":\"locked\",\"domainName\":\"example.test\",\"registrarId\":\"TheRegistrar\"}");
    assertThat(loadByEntity(defaultDomain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_unlock() {
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createDefaultLockBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "{\"action\":\"unlocked\",\"domainName\":\"example.test\",\"registrarId\":\"TheRegistrar\"}");
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testSuccess_admin_lock() {
    saveRegistryLock(createDefaultLockBuilder().isSuperuser(true).build());
    user =
        user.asBuilder()
            .setUserRoles(user.getUserRoles().asBuilder().setIsAdmin(true).build())
            .build();
    action = createAction(DEFAULT_CODE);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "{\"action\":\"locked\",\"domainName\":\"example.test\",\"registrarId\":\"TheRegistrar\"}");
    assertThat(loadByEntity(defaultDomain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_admin_unlock() {
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createDefaultLockBuilder()
            .isSuperuser(true)
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build());
    user =
        user.asBuilder()
            .setUserRoles(user.getUserRoles().asBuilder().setIsAdmin(true).build())
            .build();
    action = createAction(DEFAULT_CODE);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "{\"action\":\"unlocked\",\"domainName\":\"example.test\",\"registrarId\":\"TheRegistrar\"}");
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testFailure_invalidCode() {
    saveRegistryLock(createDefaultLockBuilder().setVerificationCode("foobar").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Invalid verification code \"123456789ABCDEFGHJKLMNPQRSTUUUUU\"");
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testFailure_expiredLock() {
    saveRegistryLock(createDefaultLockBuilder().build());
    fakeClock.advanceBy(Duration.standardDays(1));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("The pending lock has expired; please try again");
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testFailure_nonAdmin_lock() {
    saveRegistryLock(createDefaultLockBuilder().isSuperuser(true).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Non-admin user cannot complete admin lock");
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testFailure_nonAdmin_unlock() {
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createDefaultLockBuilder()
            .isSuperuser(true)
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Non-admin user cannot complete admin unlock");
    assertThat(loadByEntity(defaultDomain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  private RegistryLock.Builder createDefaultLockBuilder() {
    return new RegistryLock.Builder()
        .setRepoId(defaultDomain.getRepoId())
        .setDomainName(defaultDomain.getDomainName())
        .setRegistrarId(defaultDomain.getCurrentSponsorRegistrarId())
        .setRegistrarPocId("johndoe@theregistrar.com")
        .setVerificationCode(DEFAULT_CODE);
  }

  private ConsoleRegistryLockVerifyAction createAction(String verificationCode) {
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(authResult);
    when(params.request().getMethod()).thenReturn("GET");
    when(params.request().getServerName()).thenReturn("registrarconsole.tld");
    DomainLockUtils domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(StringGenerator.Alphabets.BASE_58),
            "adminreg",
            new CloudTasksHelper(fakeClock).getTestCloudTasksUtils());
    response = (FakeResponse) params.response();
    return new ConsoleRegistryLockVerifyAction(params, domainLockUtils, verificationCode);
  }
}
