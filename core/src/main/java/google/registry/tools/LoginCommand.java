// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.extensions.java6.auth.oauth2.GooglePromptReceiver;
import javax.inject.Inject;

/** Authorizes the nomulus tool for OAuth 2.0 access to remote resources. */
@Parameters(commandDescription = "Create local OAuth credentials")
final class LoginCommand implements Command {

  @Inject GoogleAuthorizationCodeFlow flow;
  @Inject @AuthModule.ClientScopeQualifier String clientScopeQualifier;

  @Parameter(
      names = "--remote",
      description =
          "Whether the command is run on a remote host where access to a browser is not available. "
              + "If set to true, a URL will be given and a code is expected to be entered after "
              + "the user completes authorization by visiting that URL.")
  private boolean remote = false;

  @Override
  public void run() throws Exception {
    AuthorizationCodeInstalledApp app;
    if (remote) {
      app =
          new AuthorizationCodeInstalledApp(
              flow,
              new GooglePromptReceiver(),
              url -> {
                System.out.println("Please open the following address in your browser:");
                System.out.println("  " + url);
              });
    } else {
      app = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver());
    }
    app.authorize(clientScopeQualifier);
  }
}
