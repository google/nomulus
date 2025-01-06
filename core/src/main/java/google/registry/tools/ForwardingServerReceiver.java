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

package google.registry.tools;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import java.io.IOException;

/**
 * A thin wrapper around {@link LocalServerReceiver} which points the redirect URI to a different
 * port (the forwarding port) while still listening on the random unused port (the remote port)
 * nomulus itself picks. This allows us to run the nomulus tool on a remote host (which one can SSH
 * into) while performing the OAuth 3-legged login flow in a local browser (from the lost host where
 * the SSH client resides).
 *
 * <p>When performing the login flow, an HTTP server will be listening on the remote port and have a
 * redirect_uri of <code>http://localhost:remote_port</code>, which is only accessible from the
 * remote host. By changing the redirect_uri to <code>http://localhost:forwarding_port</code>, it
 * becomes accessible from the local host, if <code>local_host:forwarding_port</code> is forwarded
 * to <code>remote_host:remote_port</code>.
 *
 * <p>Note that port forwarding is <b>required</b>. We cannot use the remote host's IP or reverse
 * DNS address in the redirect URI, even if they are directly accessible from the local host,
 * because the only allowed redirect URI scheme for desktops apps when sending a request to the
 * Google OAuth server is the loopback address with a port.
 *
 * @see <href
 *     a=https://developers.google.com/identity/protocols/oauth2/native-app#request-parameter-redirect_uri>
 *     redirect_uri values </href>
 */
final class ForwardingServerReceiver implements VerificationCodeReceiver {

  private final int forwardingPort;
  private final LocalServerReceiver localServerReceiver = new LocalServerReceiver();

  ForwardingServerReceiver(int forwardingPort) {
    this.forwardingPort = forwardingPort;
  }

  @Override
  public String getRedirectUri() throws IOException {
    String redirectUri = localServerReceiver.getRedirectUri();
    return redirectUri.replace("localhost:" + getRemotePort(), "localhost:" + forwardingPort);
  }

  @Override
  public String waitForCode() throws IOException {
    return localServerReceiver.waitForCode();
  }

  @Override
  public void stop() throws IOException {
    localServerReceiver.stop();
    System.out.println("You can now exit from the SSH session created for port forwarding.");
  }

  int getRemotePort() {
    return localServerReceiver.getPort();
  }
}
