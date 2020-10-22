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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Security;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.random.SingletonRandomFactory;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/** In-process SFTP server using Apache SSHD. */
public class TestSftpServer implements FtpServer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static SingletonRandomFactory secureRandomFactory;

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  private static final String HOST_KEY = ""
      + "-----BEGIN RSA PRIVATE KEY-----\n"
      + "MIIEowIBAAKCAQEAx7uuoDyMR3c+sIg0fPfBeyoUjPa6hh3yN5S4/HLEOOaXcA2c\n"
      + "uhNm8WwIXF/FwC32CJHZcCC3XhNeQDIpEt5wj9NHr++pjYWjp4Ue+n/2QbDO4LKB\n"
      + "fb67zRbL++BZNswBKYwmzaiHoyWYERj+ZrZpf7hqHnKTAs4NIUCkhUvUHQzgdfT6\n"
      + "vfEYv2HrM2VVVaqpUALrkogZHqQYfLA+InHXXZ3x+GmdSBCxaxUHHZypwETv+yBJ\n"
      + "YAP+9Ofcmu3mpedK/3o3KJ2pD4OXwoXAtbHDOfnJMJylCKcCT3aodpdULSf4A8Zm\n"
      + "o4fxppQCbGUw43D7u18he5qNdLDkgCV8iXewbQIDAQABAoIBAH/Fs9e0BDVvtj3u\n"
      + "VE2hnTeyWsU2zWog3CPsU07ECH0yHqzDOIDdCpzk9JBLgFEJ1fvzebs+Yq+fCktd\n"
      + "C2OTw0Ru78xAMCJl3KS9B21O0PWDK0UZTLdpffCcZc/y3H+ukAvJKcWky2h2E0rU\n"
      + "x2JjzSe0jMZ/m0ZPFJ0yIk1XjhEqWgp3OCP9EHaO82+GM8UTuoBrfOs8gcv6aqiO\n"
      + "L06BU6a6i75chUtZbSadaUnZOE8tIyXit6p63bBHp2oN4D8FiOPBuTDwFEI4zrA4\n"
      + "vNcfRvKQWnQjlWm91BCajNxVqWi7XtWK7ikmwefZWNcd83RSf6vfb8ThpjwHmBaE\n"
      + "dbbL9i0CgYEA+KwB5qiaRxryBI1xqQaH6HiQ5WtW06ft43l2FrTZGPocjyF7KyWn\n"
      + "lS5q0J4tdSSkNfXqhT8aosm3VjfIxoDuQQO0ptD+km5qe1xu/qxLHJYd5FP67X9O\n"
      + "66e8sxcDSuKSvZ6wNeKNOL5SC7dDLCuMZoRTvXxGneg9a1w2x1MVrZsCgYEAzZ56\n"
      + "cqqNvavj4x2yDG+4oAB4/sQvVFK2+PopFpO5w8ezLDVfnoIv56gfmqMN9UdZH8Ew\n"
      + "PJhuEFRcdePo2ZnNjsWf9NhniJSMsEPTIc5qOPyoo5DcQM6DU8f4HC236uR+5P0h\n"
      + "jLfKRpvZl+N3skJi98QQr9PeLyb0sM8zRbZ0fpcCgYA9nuIZtk4EsLioSCSSLfwf\n"
      + "r0C4mRC7AjIA3GhW2Bm0BsZs8W8EEiCk5wuxBoFdNec7N+UVf72p+TJlOw2Vov1n\n"
      + "PvPVIpTy1EmuqAkZMriqLMjbe7QChjmYS8iG2H0IYXzbYCdqMumr1f2eyZrrpx7z\n"
      + "iHb3zYPyPUp7AC7S1dPZYQKBgQCK0p+LQVk3IJFYalkmiltVM1x9bUkjHkFIseUB\n"
      + "yDUYWIDArTxkkTL0rY7A4atv2X7zsIP3tVZCEiLmuTwhhfTBmu3W6jBkhx7Bdtla\n"
      + "LrmKxhK5c/kwi/0gmJcLt1Y/8Ys24SxAjGm16E0tfjb3FFkrPKWjgGC25w83PH06\n"
      + "aOgX+wKBgBLkLh/rwpiD4e8ZVjGuCn9A0H/2KZknXyNbkVjPho0FXBKIlfMa6c34\n"
      + "fRLDvHVZ0xo4dQxp38Wg+ZzIwGoAHhuJpSsfMsWKVXwNwV4iMEt2zyZRomui3TzT\n"
      + "+8awtDyALwvL05EBuxctT3iFGQcUj/fNCi0PeLoTSscdH2pdvHTb\n"
      + "-----END RSA PRIVATE KEY-----\n";

  private static final String KEY_TYPE = "ssh-rsa";

  private static final KeyPair HOST_KEY_PAIR = createKeyPair(HOST_KEY);

  @Nullable
  private static KeyPair createKeyPair(String key) {
    try (PEMParser pemParser = new PEMParser(new StringReader(key))) {
      PEMKeyPair pemPair = (PEMKeyPair) pemParser.readObject();
      KeyPair result = new JcaPEMKeyConverter().setProvider("BC").getKeyPair(pemPair);
      logger.atInfo().log("Read key pair successfully.");
      return result;
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Couldn't read key pair from string.");
      return null;
    }
  }

  public static FtpServer createSftpServer(
      final String authorizedUser,
      @Nullable final String authorizedPassword,
      @Nullable final PublicKey authorizedPublicKey,
      int port,
      final File home) {

    ServerBuilder serverBuilder = ServerBuilder.builder();
    serverBuilder.randomFactory(secureRandomFactory);

    if (authorizedPublicKey != null) {
      // This authenticator checks that the user is presenting the right key. If authenticate
      // returns true, then the server will make sure that the user can prove they have that key.
      // Not that you would know this from the Apache javadocs.
      serverBuilder.publickeyAuthenticator(
          new PublickeyAuthenticator() {
            @Override
            public boolean authenticate(
                String username, PublicKey publicKey, ServerSession session) {
              return Arrays.equals(publicKey.getEncoded(), authorizedPublicKey.getEncoded());
            }
          });
    }

    serverBuilder.fileSystemFactory(new VirtualFileSystemFactory(home.toPath()));

    SshServer server = serverBuilder.build();
    server.setCommandFactory(new ScpCommandFactory());
    server.setPort(port);

    NamedFactory<Command> sftpSubsystemFactory = new SftpSubsystemFactory.Builder().build();
    server.setSubsystemFactories(ImmutableList.of(sftpSubsystemFactory));

    if (authorizedPassword != null) {
      server.setPasswordAuthenticator(
          new PasswordAuthenticator() {
            @Override
            public boolean authenticate(String username, String password, ServerSession session) {
              return username.equals(authorizedUser) && password.equals(authorizedPassword);
            }
          });
    }

    KeyPairProvider keyPairProvider =
        new KeyPairProvider() {
          final ImmutableMap<String, KeyPair> keyPairByTypeMap =
              ImmutableMap.of(KEY_TYPE, HOST_KEY_PAIR);

          @Override
          public Iterable<KeyPair> loadKeys() {
            return keyPairByTypeMap.values();
          }

          @Override
          public Iterable<String> getKeyTypes() {
            return keyPairByTypeMap.keySet();
          }

          @Override
          public KeyPair loadKey(final String type) {
            return keyPairByTypeMap.get(type);
          }
        };
    server.setKeyPairProvider(keyPairProvider);

    return new TestSftpServer(server);
  }

  private final SshServer server;

  private boolean stopped = true;

  private TestSftpServer(SshServer server) {
    this.server = server;
  }

  @Override
  public void suspend() {
    // NOP
  }

  @Override
  public synchronized void stop() {
    try {
      logger.atInfo().log("Stopping server");
      server.stop(true);
      stopped = true;
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error shutting down server");
    }
  }

  @Override
  public synchronized void start() throws FtpException {
    try {
      logger.atInfo().log("Starting server");
      server.start();
      stopped = false;
    } catch (IOException e) {
      throw new FtpException("Couldn't start server", e);
    }
  }

  @Override
  public void resume() {
    // NOP
  }

  @Override
  public boolean isSuspended() {
    return false;
  }

  @Override
  public boolean isStopped() {
    return stopped;
  }
}
