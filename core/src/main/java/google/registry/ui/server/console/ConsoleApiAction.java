package google.registry.ui.server.console;

import static google.registry.request.Action.Method.GET;

import com.google.api.client.http.HttpStatusCodes;
import google.registry.model.console.User;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.RegistrarConsoleModule.ConsoleApiParams;
import java.util.Arrays;
import java.util.Optional;
import javax.servlet.http.Cookie;

public class ConsoleApiAction implements Runnable {
  private ConsoleApiParams consoleApiParams;

  public ConsoleApiAction(ConsoleApiParams consoleApiParams) {
    this.consoleApiParams = consoleApiParams;
  }

  public final void run() {
    if (!consoleApiParams.getAuthResult().userAuthInfo().get().consoleUser().isPresent()) {
      consoleApiParams.getRsp().setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return;
    }
    User user = consoleApiParams.getAuthResult().userAuthInfo().get().consoleUser().get();
    if (consoleApiParams.getReq().getMethod().equals(GET.toString())) {
      getHandler(user);
    } else {
      if (verifyXSRF()) {
        postHandler(user);
      }
    }
  }
  ;

  protected void postHandler(User user) {
    throw new RuntimeException("Console API POST handler not implemented");
  }

  protected void getHandler(User user) {
    throw new RuntimeException("Console API GET handler not implemented");
  }

  private boolean verifyXSRF() {
    Optional<Cookie> maybeCookie =
        Arrays.stream(consoleApiParams.getReq().getCookies())
            .filter(c -> XsrfTokenManager.X_CSRF_TOKEN.equals(c.getName()))
            .findFirst();
    if (!maybeCookie.isPresent()
        || !consoleApiParams.getXsrfTokenManager().validateToken(maybeCookie.get().getValue())) {
      consoleApiParams.getRsp().setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return false;
    }
    return true;
  }
}
