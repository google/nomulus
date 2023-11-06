package google.registry.testing;

import static org.mockito.Mockito.mock;

import com.google.appengine.api.users.UserService;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.RegistrarConsoleModule.ConsoleApiParams;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

public final class FakeConsoleApiParams {

  public static ConsoleApiParams get(Optional<AuthResult> maybeAuthResult) {
    AuthResult authResult =
        maybeAuthResult.orElseGet(
            () ->
                AuthResult.createUser(
                    UserAuthInfo.create(
                        new com.google.appengine.api.users.User(
                            "JohnDoe@theregistrar.com", "theregistrar.com"),
                        false)));
    return new ConsoleApiParams(
        mock(HttpServletRequest.class),
        new FakeResponse(),
        authResult,
        new XsrfTokenManager(
            new FakeClock(DateTime.parse("2020-02-02T01:23:45Z")), mock(UserService.class)));
  }
}
