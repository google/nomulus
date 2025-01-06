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

package google.registry.rde;

import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import google.registry.config.RegistryConfig.Config;
import java.io.Closeable;
import java.net.URI;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * SFTP connection {@link Session} delegate that implements {@link Closeable}.
 *
 * <p>This class acts as syntactic sugar for JSch so we can open and close SFTP connections in a
 * way that's friendlier to Java 7 try-resource statements. Delegate methods are provided on an
 * as-needed basis.
 *
 * @see JSchSftpChannel
 * @see RdeUploadAction
 * @see com.jcraft.jsch.Session
 */
final class JSchSshSession implements Closeable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Factory for {@link JSchSshSession}. */
  static final class JSchSshSessionFactory {

    private final Duration sshTimeout;

    @Inject
    JSchSshSessionFactory(@Config("sshTimeout") Duration sshTimeout) {
      this.sshTimeout = sshTimeout;
    }

    /**
     * Connect to remote SSH endpoint specified by {@code url}.
     *
     * @throws JSchException if we fail to open the connection.
     */
    JSchSshSession create(JSch jsch, URI uri) throws JSchException {
      RdeUploadUrl url = RdeUploadUrl.create(uri);
      logger.atInfo().log("Connecting to SSH endpoint: %s", url);
      Session session = jsch.getSession(
          url.getUser().orElse("domain-registry"),
          url.getHost(),
          url.getPort());
      if (url.getPass().isPresent()) {
        session.setPassword(url.getPass().get());
      }
      session.setTimeout((int) sshTimeout.getMillis());
      session.connect((int) sshTimeout.getMillis());
      return new JSchSshSession(session, url, (int) sshTimeout.getMillis());
    }
  }

  private final Session session;
  private final RdeUploadUrl url;
  private final int timeout;

  private JSchSshSession(Session session, RdeUploadUrl url, int timeout) {
    this.session = session;
    this.url = url;
    this.timeout = timeout;
  }

  /**
   * Opens a new SFTP channel over this SSH session.
   *
   * @see JSchSftpChannel
   */
  public JSchSftpChannel openSftpChannel() throws JSchException, SftpException {
    ChannelSftp chan = (ChannelSftp) session.openChannel("sftp");
    chan.connect(timeout);
    if (url.getPath().isPresent()) {
      String dir = url.getPath().get();
      try {
        chan.cd(dir);
      } catch (SftpException e) {
        logger.atWarning().withCause(e).log("Could not open SFTP channel.");
        mkdirs(chan, dir);
        chan.cd(dir);
      }
    }
    return new JSchSftpChannel(chan);
  }

  private void mkdirs(ChannelSftp chan, String dir) throws SftpException {
    StringBuilder pathBuilder = new StringBuilder(dir.length());
    for (String part : Splitter.on('/').omitEmptyStrings().split(dir)) {
      pathBuilder.append(part);
      chan.mkdir(pathBuilder.toString());
      pathBuilder.append('/');
    }
  }

  /** @see com.jcraft.jsch.Session#disconnect() */
  @Override
  public void close() {
    session.disconnect();
  }
}
