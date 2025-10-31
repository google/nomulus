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

package google.registry.rdap;

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * RDAP action that serves the empty string, redirecting to the help page.
 *
 * <p>This isn't technically required, but if someone requests the base url it seems nice to give
 * them the help response.
 */
@Action(
    service = Action.Service.PUBAPI,
    path = "/rdap/",
    method = {GET, HEAD},
    auth = Auth.AUTH_PUBLIC)
public class RdapEmptyAction implements Runnable {

  private final Response response;

  @Inject
  public RdapEmptyAction(Response response) {
    this.response = response;
  }

  @Override
  public void run() {
    try {
      response.sendRedirect(RdapHelpAction.PATH);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
