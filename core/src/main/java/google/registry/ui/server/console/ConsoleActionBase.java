package google.registry.ui.server.console;

import com.google.api.client.http.HttpStatusCodes;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.request.Response;
import google.registry.request.auth.AuthResult;

public abstract class ConsoleActionBase implements Runnable {

  private static boolean isAuthenticated(AuthResult authResult) {
    if (!authResult.isAuthenticated()
        || !authResult.userAuthInfo().isPresent()
        || !authResult.userAuthInfo().get().consoleUser().isPresent()) {
      return false;
    }
    return true;
  }

  private static boolean isAuthorizedRead(User user, String registrarId) {
    return user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_REGISTRAR_DETAILS)
        || user.getUserRoles().hasPermission(registrarId, ConsolePermission.VIEW_REGISTRAR_DETAILS);
  }

  private static boolean isAuthorizedWrite(User user, String registrarId) {
    return user.getUserRoles().hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)
        || user.getUserRoles().hasPermission(registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
  }

  protected static boolean verifyUserPermissions(
      boolean isReadOperation, String registrarId, AuthResult authResult, Response response) {
    if (!isAuthenticated(authResult)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return false;
    }

    User user = authResult.userAuthInfo().get().consoleUser().get();
    boolean isAuthorized =
        isReadOperation
            ? isAuthorizedRead(user, registrarId)
            : isAuthorizedWrite(user, registrarId);

    if (!isAuthorized) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return false;
    }
    return true;
  }

  public abstract void run();
}
