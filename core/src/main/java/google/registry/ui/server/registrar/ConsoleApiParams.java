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

package google.registry.ui.server.registrar;

import google.registry.groups.GmailClient;
import google.registry.request.Response;
import google.registry.request.auth.AuthResult;
import google.registry.security.XsrfTokenManager;
import jakarta.servlet.http.HttpServletRequest;

/** Groups necessary dependencies for Console API actions * */
public record ConsoleApiParams(
    HttpServletRequest request,
    Response response,
    AuthResult authResult,
    GmailClient gmailClient,
    XsrfTokenManager xsrfTokenManager) {
  public static ConsoleApiParams create(
      HttpServletRequest request,
      Response response,
      AuthResult authResult,
      GmailClient gmailClient,
      XsrfTokenManager xsrfTokenManager) {
    return new ConsoleApiParams(request, response, authResult, gmailClient, xsrfTokenManager);
  }
}
