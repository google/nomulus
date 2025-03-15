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
import jakarta.inject.Inject;
import java.net.InetAddress;

/** Authorizes the nomulus tool for OAuth 2.0 access to remote resources. */
@Parameters(commandDescription = "Create local OAuth credentials")
final class LoginCommand implements Command {

  @Inject GoogleAuthorizationCodeFlow flow;
  @Inject @AuthModule.ClientScopeQualifier String clientScopeQualifier;

  @Parameter(
      names = "--port",
      description =
          "A free port on the local host. When set, it is assumed that the nomulus tool runs on a"
              + " remote host whose browser is not accessible locally. i. e. if you SSH to a"
              + " machine and run `nomulus` there, the ssh client is on the local host and nomulus"
              + " runs on a remote host. You will need to forward the local port specified here to"
              + " a remote port that nomulus randomly picks. Follow the instruction when prompted.")
  private int port = 0;

  @Override
  public void run() throws Exception {
    AuthorizationCodeInstalledApp app;
    if (port != 0) {
      String remoteHost = InetAddress.getLocalHost().getHostName();
      ForwardingServerReceiver forwardingServerReceiver = new ForwardingServerReceiver(port);
      app =
          new AuthorizationCodeInstalledApp(
              flow,
              forwardingServerReceiver,
              url -> {
                int remotePort = forwardingServerReceiver.getRemotePort();
                System.out.printf(
                    """
                    Please first run the following command in a separate terminal on your local\
                     host:

                      ssh -L %s:localhost:%s %s

                    """,
                    port, remotePort, remoteHost);
                System.out.printf(
                    """
                    Please then open the following URL in your local browser and follow the\
                     instructions:

                      %s

                    """,
                    url);
              });
    } else {
      app = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver());
    }
    app.authorize(clientScopeQualifier);
  }
}
