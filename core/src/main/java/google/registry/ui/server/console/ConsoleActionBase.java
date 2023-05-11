// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import com.google.api.client.http.HttpStatusCodes;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.request.Response;
import google.registry.request.auth.AuthResult;

public abstract class ConsoleActionBase implements Runnable {

  private static boolean isAuthenticated(AuthResult authResult) {
    return authResult.isAuthenticated()
        && authResult.userAuthInfo().isPresent()
        && authResult.userAuthInfo().get().consoleUser().isPresent();
  }

  private static boolean isAuthorizedRead(User user, String registrarId) {
    return user.getUserRoles().hasPermission(registrarId, ConsolePermission.VIEW_REGISTRAR_DETAILS);
  }

  private static boolean isAuthorizedWrite(User user, String registrarId) {
    return user.getUserRoles().hasPermission(registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
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
}
