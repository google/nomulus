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

package google.registry.testing.sftp;

import static com.google.common.base.Preconditions.checkState;

import google.registry.util.NetworkUtils;
import java.io.File;
import javax.annotation.Nullable;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.ftplet.FtpException;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension for creating an in-process {@link TestSftpServer SFTP Server}.
 *
 * @see TestSftpServer
 */
public final class SftpServerExtension implements AfterEachCallback {

  @Nullable
  private FtpServer server;

  /**
   * Starts an SFTP server on a randomly selected port.
   *
   * @return the port on which the server is listening
   */
  public int serve(String user, String pass, File home) throws FtpException {
    checkState(server == null, "You already have an SFTP server!");
    int port = NetworkUtils.pickUnusedPort();
    server = createSftpServer(user, pass, home, port);
    return port;
  }

  @Override
  public void afterEach(ExtensionContext context) {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  private static FtpServer createSftpServer(String user, String pass, File home, int port)
      throws FtpException {
    FtpServer server = TestSftpServer.createSftpServer(user, pass, null, port, home);
    server.start();
    return server;
  }
}
