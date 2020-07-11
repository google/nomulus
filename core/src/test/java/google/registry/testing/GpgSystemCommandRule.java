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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * GnuPG system command JUnit rule.
 *
 * <p>This rule creates a isolated environment for running the {@code gpg} command inside system
 * integration tests. It reduces a lot of the boilerplate of setting up the shell environment and
 * importing your keyrings into a temporary config folder.
 */
public final class GpgSystemCommandRule implements BeforeEachCallback, AfterEachCallback {

  private static final File DEV_NULL = new File("/dev/null");
  private static final String TEMP_FILE_PREFIX = "gpgtest";

  private File cwd = DEV_NULL;
  private File conf = DEV_NULL;
  private String[] env = {};
  private final ByteSource publicKeyring;
  private final ByteSource privateKeyring;
  private final Runtime runtime = Runtime.getRuntime();

  /** Constructs a new {@link GpgSystemCommandRule} instance. */
  public GpgSystemCommandRule(ByteSource publicKeyring, ByteSource privateKeyring) {
    this.publicKeyring = checkNotNull(publicKeyring, "publicKeyring");
    this.privateKeyring = checkNotNull(privateKeyring, "privateKeyring");
  }

  /** Returns the temporary directory from which commands are run. */
  public File getCwd() {
    checkState(!Objects.equals(cwd, DEV_NULL));
    return cwd;
  }

  /** Returns the temporary directory in which GnuPG configs are stored. */
  public File getConf() {
    checkState(!Objects.equals(conf, DEV_NULL));
    return conf;
  }

  /**
   * Runs specified system command and arguments within the GPG testing environment.
   *
   * @see Runtime#exec(String[])
   */
  public final Process exec(String... args) throws IOException {
    checkState(!Objects.equals(cwd, DEV_NULL));
    checkArgument(args.length > 0, "args");
    return runtime.exec(args, env, cwd);
  }

  @Override
  public void beforeEach(ExtensionContext context) throws IOException, InterruptedException {
    checkState(Objects.equals(cwd, DEV_NULL));
    String tmpRootDirString = System.getenv("TMPDIR");
    // Create the working directory for the forked process on Temp file system. Create under the
    // path specified by 'TMPDIR' envrionment variable if defined, otherwise create under the
    // runtime's default (typically /tmp).
    cwd =
        isNullOrEmpty(tmpRootDirString)
            ? File.createTempFile(TEMP_FILE_PREFIX, "")
            : File.createTempFile(TEMP_FILE_PREFIX, "", new File(tmpRootDirString));
    cwd.delete();
    cwd.mkdir();
    conf = new File(cwd, ".gnupg");
    conf.mkdir();
    Files.setPosixFilePermissions(conf.toPath(), PosixFilePermissions.fromString("rwx------"));
    env =
        new String[] {
          "PATH=" + System.getenv("PATH"), "GNUPGHOME=" + conf.getAbsolutePath(),
        };

    Process pid = exec("gpg", "--import");
    publicKeyring.copyTo(pid.getOutputStream());
    pid.getOutputStream().close();
    int returnValue = pid.waitFor();
    assertWithMessage(
            String.format("Failed to import public keyring: \n%s", slurp(pid.getErrorStream())))
        .that(returnValue)
        .isEqualTo(0);

    pid = exec("gpg", "--allow-secret-key-import", "--import");
    privateKeyring.copyTo(pid.getOutputStream());
    pid.getOutputStream().close();
    returnValue = pid.waitFor();
    assertWithMessage(
            String.format("Failed to import private keyring: \n%s", slurp(pid.getErrorStream())))
        .that(returnValue)
        .isEqualTo(0);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // TODO(weiminyu): we should delete the cwd tree.
    cwd = DEV_NULL;
    conf = DEV_NULL;
  }

  private String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
