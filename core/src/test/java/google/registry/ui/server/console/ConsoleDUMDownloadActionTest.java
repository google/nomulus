package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeConsoleApiParams;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.registrar.ConsoleApiParams;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

class ConsoleDUMDownloadActionTest {

  private static final Gson GSON = GsonUtils.provideGson();

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  private ConsoleApiParams consoleApiParams;

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    for (int i = 0; i < 3; i++) {
      DatabaseHelper.persistActiveDomain(
          i + "exists.tld", clock.nowUtc(), clock.nowUtc().plusDays(300));
      clock.advanceOneMilli();
    }
    DatabaseHelper.persistDeletedDomain("deleted.tld", clock.nowUtc().minusDays(1));
  }

  @Test
  void testSuccess_returnsCorrectDomains() {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();

    AuthResult authResult = AuthResult.createUser(UserAuthInfo.create(user));
    ConsoleDUMDownloadAction action = createAction(Optional.of(authResult));
    action.run();
    ImmutableList<String> expected =
        ImmutableList.of(
            "Domain Name,Creation Time,Expiration Time,Domain Statuses",
            "2exists.tld,2024-04-15 00:00:00.002+00,2025-02-09 00:00:00.002+00,{INACTIVE}",
            "1exists.tld,2024-04-15 00:00:00.001+00,2025-02-09 00:00:00.001+00,{INACTIVE}",
            "0exists.tld,2024-04-15 00:00:00+00,2025-02-09 00:00:00+00,{INACTIVE}");
    FakeResponse response = (FakeResponse) consoleApiParams.response();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    ImmutableList<String> actual = ImmutableList.copyOf(response.writer.toString().split("\r\n"));
    assertThat(actual).containsExactlyElementsIn(expected);
  }

  @Test
  void testFailure_forbidden() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).setIsAdmin(false).build();

    User user =
        new User.Builder().setEmailAddress("email@email.com").setUserRoles(userRoles).build();

    AuthResult authResult = AuthResult.createUser(UserAuthInfo.create(user));
    ConsoleDUMDownloadAction action = createAction(Optional.of(authResult));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
  }

  ConsoleDUMDownloadAction createAction(Optional<AuthResult> maybeAuthResult) {
    consoleApiParams = FakeConsoleApiParams.get(maybeAuthResult);
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.GET.toString());
    return new ConsoleDUMDownloadAction(clock, consoleApiParams, "TheRegistrar");
  }
}
