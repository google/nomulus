// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import google.registry.testing.AppEngineRule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.tools.RegistryTool}. */
public class RegistryToolTest {

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  // Lock for stdout/stderr.  Note that this is static: since we're dealing with globals, we need
  // to lock for the entire JVM.
  private static final ReentrantLock stdoutLock = new ReentrantLock();

  private PrintStream oldStdout;
  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

  @BeforeEach
  void beforeEach() {
    // Capture standard output/error. This is problematic because gradle tests run in parallel in
    // the same JVM.  So first lock out any other tests in this JVM that are trying to do this
    // trick.
    // TODO(mcilwain): Turn this into a JUnit 5 extension.
    stdoutLock.lock();
    oldStdout = System.out;
    System.setOut(new PrintStream(stdout));
  }

  @AfterEach
  final void afterEach() {
    System.setOut(oldStdout);
    stdoutLock.unlock();
  }

  @Test
  void test_displayAvailableCommands() throws Exception {
    RegistryTool.main(new String[] {"-e", "unittest", "--commands"});
    // Check for the existence of a few common commands.
    assertThat(getStdout()).contains("login");
    assertThat(getStdout()).contains("check_domain");
    assertThat(getStdout()).contains("get_tld");
  }

  @Test
  void test_noSubcommandSpecified_displaysAvailableCommands() throws Exception {
    RegistryTool.main(new String[] {"-e", "unittest"});
    assertThat(getStdout()).contains("The list of available subcommands is:");
    assertThat(getStdout()).contains("login");
    assertThat(getStdout()).contains("check_domain");
    assertThat(getStdout()).contains("get_tld");
  }

  @Test
  void test_noEnvironmentSpecified_throwsCorrectError() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> RegistryTool.main(new String[] {}));
    assertThat(thrown).hasMessageThat().contains("Please specify the environment flag");
  }

  private String getStdout() {
    return new String(stdout.toByteArray(), UTF_8);
  }
}
