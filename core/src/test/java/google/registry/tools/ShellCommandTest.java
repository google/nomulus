// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.MissingCommandException;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.FakeClock;
import google.registry.testing.SystemPropertyRule;
import google.registry.tools.ShellCommand.JCommanderCompletor;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ShellCommand}. */
class ShellCommandTest {

  @RegisterExtension final SystemPropertyRule systemPropertyRule = new SystemPropertyRule();

  CommandRunner cli = mock(CommandRunner.class);
  private FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private PrintStream orgStdout;
  private PrintStream orgStderr;

  private ByteArrayOutputStream stdout;
  private ByteArrayOutputStream stderr;

  @BeforeEach
  void beforeEach() {
    orgStdout = System.out;
    orgStderr = System.err;
  }

  @AfterEach
  void afterEach() {
    System.setOut(orgStdout);
    System.setErr(orgStderr);
  }

  @Test
  void testParsing() {
    assertThat(ShellCommand.parseCommand("foo bar 123 baz+ // comment \"string data\""))
        .isEqualTo(new String[] {"foo", "bar", "123", "baz+", "//", "comment", "string data"});
    assertThat(ShellCommand.parseCommand("\"got \\\" escapes?\""))
        .isEqualTo(new String[] {"got \" escapes?"});
    assertThat(ShellCommand.parseCommand("")).isEqualTo(new String[0]);
  }

  private ShellCommand createShellCommand(
      CommandRunner commandRunner, Duration delay, String... commands) throws Exception {
    ArrayDeque<String> queue = new ArrayDeque<String>(ImmutableList.copyOf(commands));
    BufferedReader bufferedReader = mock(BufferedReader.class);
    when(bufferedReader.readLine())
        .thenAnswer(
            (x) -> {
              clock.advanceBy(delay);
              if (queue.isEmpty()) {
                throw new IOException();
              }
              return queue.poll();
            });
    return new ShellCommand(bufferedReader, clock, commandRunner);
  }

  @Test
  void testCommandProcessing() throws Exception {
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.ZERO, "test1 foo bar", "test2 foo bar");
    shellCommand.run();
    assertThat(cli.calls)
        .containsExactly(
            ImmutableList.of("test1", "foo", "bar"), ImmutableList.of("test2", "foo", "bar"))
        .inOrder();
  }

  @Test
  void testNoIdleWhenInAlpha() throws Exception {
    RegistryToolEnvironment.ALPHA.setup(systemPropertyRule);
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  void testNoIdleWhenInSandbox() throws Exception {
    RegistryToolEnvironment.SANDBOX.setup(systemPropertyRule);
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  void testIdleWhenOverHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup(systemPropertyRule);
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(61), "test1 foo bar", "test2 foo bar");
    RuntimeException exception = assertThrows(RuntimeException.class, shellCommand::run);
    assertThat(exception).hasMessageThat().contains("Been idle for too long");
  }

  @Test
  void testNoIdleWhenUnderHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup(systemPropertyRule);
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(59), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  static class MockCli implements CommandRunner {
    public ArrayList<ImmutableList<String>> calls = new ArrayList<>();

    @Override
    public void run(String[] args) {
      calls.add(ImmutableList.copyOf(args));
    }
  }

  @Test
  void testMultipleCommandInvocations() throws Exception {
    try (RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class))) {
      RegistryToolEnvironment.UNITTEST.setup(systemPropertyRule);
      cli.setEnvironment(RegistryToolEnvironment.UNITTEST);
      cli.run(new String[] {"test_command", "-x", "xval", "arg1", "arg2"});
      cli.run(new String[] {"test_command", "-x", "otherxval", "arg3"});
      cli.run(new String[] {"test_command"});
      assertThat(TestCommand.commandInvocations)
          .containsExactly(
              ImmutableList.of("xval", "arg1", "arg2"),
              ImmutableList.of("otherxval", "arg3"),
              ImmutableList.of("default value"));
    }
  }

  @Test
  void testNonExistentCommand() {
    try (RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class))) {

      cli.setEnvironment(RegistryToolEnvironment.UNITTEST);
      assertThrows(MissingCommandException.class, () -> cli.run(new String[] {"bad_command"}));
    }
  }

  private void performJCommanderCompletorTest(
      String line, int expectedBackMotion, String... expectedCompletions) {
    JCommander jcommander = new JCommander();
    jcommander.setProgramName("test");
    jcommander.addCommand("help", new HelpCommand(jcommander));
    jcommander.addCommand("testCommand", new TestCommand());
    jcommander.addCommand("testAnotherCommand", new TestAnotherCommand());
    List<String> completions = new ArrayList<>();
    assertThat(
            line.length()
                - new JCommanderCompletor(jcommander)
                    .completeInternal(line, line.length(), completions))
        .isEqualTo(expectedBackMotion);
    assertThat(completions).containsExactlyElementsIn(expectedCompletions);
  }

  @Test
  void testCompletion_commands() {
    performJCommanderCompletorTest("", 0, "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("n", 1);
    performJCommanderCompletorTest("test", 4, "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest(" test", 4, "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest("testC", 5, "testCommand ");
    performJCommanderCompletorTest("testA", 5, "testAnotherCommand ");
  }

  @Test
  void testCompletion_help() {
    performJCommanderCompletorTest("h", 1, "help ");
    performJCommanderCompletorTest("help ", 0, "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("help testC", 5, "testCommand ");
    performJCommanderCompletorTest("help testCommand ", 0);
  }

  @Test
  void testCompletion_documentation() {
    performJCommanderCompletorTest(
        "testCommand ",
        0,
        "",
        "Main parameter: normal argument\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand ", 0, "", "Main parameter: [None]");
    performJCommanderCompletorTest(
        "testCommand -x ", 0, "", "Flag documentation: test parameter\n  (java.lang.String)");
    performJCommanderCompletorTest(
        "testAnotherCommand -x ", 0, "", "Flag documentation: [No documentation available]");
    performJCommanderCompletorTest(
        "testCommand x ",
        0,
        "",
        "Main parameter: normal argument\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand x ", 0, "", "Main parameter: [None]");
  }

  @Test
  void testCompletion_arguments() {
    performJCommanderCompletorTest("testCommand -", 1, "-x ", "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testCommand --wrong", 7);
    performJCommanderCompletorTest("testCommand noise  --", 2, "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testAnotherCommand --o", 3);
  }

  @Test
  void testCompletion_enum() {
    performJCommanderCompletorTest("testCommand --xorg P", 1, "PRIVATE ", "PUBLIC ");
    performJCommanderCompletorTest("testCommand --xorg PU", 2, "PUBLIC ");
    performJCommanderCompletorTest(
        "testCommand --xorg ", 0, "", "Flag documentation: test organization\n  (PRIVATE, PUBLIC)");
  }

  @Test
  void testEncapsulatedOutputStream_basicFuncionality() {
    ByteArrayOutputStream backing = new ByteArrayOutputStream();
    try (PrintStream out =
        new PrintStream(new ShellCommand.EncapsulatingOutputStream(backing, "out: "))) {
      out.println("first line");
      out.print("second line\ntrailing data");
    }
    assertThat(backing.toString())
        .isEqualTo("out: first line\nout: second line\nout: trailing data\n");
  }

  @Test
  void testEncapsulatedOutputStream_emptyStream() {
    ByteArrayOutputStream backing = new ByteArrayOutputStream();
    try (PrintStream out =
        new PrintStream(new ShellCommand.EncapsulatingOutputStream(backing, "out: "))) {}
    assertThat(backing.toString()).isEqualTo("");
  }

  @Test
  void testEncapsulatedOutput_command() throws Exception {
    RegistryToolEnvironment.ALPHA.setup(systemPropertyRule);
    captureOutput();
    ShellCommand shellCommand =
        new ShellCommand(
            args -> {
              System.out.println("first line");
              System.err.println("second line");
              System.out.print("fragmented ");
              System.err.println("surprise!");
              System.out.println("line");
            });
    shellCommand.encapsulateOutput = true;

    shellCommand.run();
    assertThat(stderr.toString()).isEmpty();
    assertThat(stdout.toString())
        .isEqualTo(
            "RUNNING \"command1\"\n"
                + "out: first line\nerr: second line\nerr: surprise!\nout: fragmented line\n"
                + "SUCCESS\n");
  }

  @Test
  void testEncapsulatedOutput_throws() throws Exception {
    RegistryToolEnvironment.ALPHA.setup(systemPropertyRule);
    captureOutput();
    ShellCommand shellCommand =
        new ShellCommand(
            args -> {
              System.out.println("first line");
              throw new Exception("some error!");
            });
    shellCommand.encapsulateOutput = true;
    shellCommand.run();
    assertThat(stderr.toString()).isEmpty();
    assertThat(stdout.toString())
        .isEqualTo(
            "RUNNING \"command1\"\n"
                + "out: first line\n"
                + "FAILURE java.lang.Exception some error!\n");
  }

  @Test
  void testEncapsulatedOutput_noCommand() throws Exception {
    captureOutput();
    ShellCommand shellCommand =
        createShellCommand(
            args -> {
              System.out.println("first line");
            },
            Duration.ZERO,
            "",
            "do something");
    shellCommand.encapsulateOutput = true;
    shellCommand.run();
    assertThat(stderr.toString()).isEmpty();
    assertThat(stdout.toString())
        .isEqualTo("RUNNING \"do\" \"something\"\nout: first line\nSUCCESS\n");
  }

  void captureOutput() {
    // capture output (have to do this before the shell command is created)
    stdout = new ByteArrayOutputStream();
    stderr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(stdout));
    System.setErr(new PrintStream(stderr));
    System.setIn(new ByteArrayInputStream("command1\n".getBytes(UTF_8)));
  }

  @Parameters(commandDescription = "Test command")
  static class TestCommand implements Command {
    enum OrgType {
      PRIVATE,
      PUBLIC
    }

    @Parameter(
        names = {"-x", "--xparam"},
        description = "test parameter")
    String xparam = "default value";

    @Parameter(
        names = {"--xorg"},
        description = "test organization")
    OrgType orgType = OrgType.PRIVATE;

    // List for recording command invocations by run().
    //
    // This has to be static because it gets populated by multiple TestCommand instances, which are
    // created in RegistryCli by using reflection to call the constructor.
    static final List<List<String>> commandInvocations = new ArrayList<>();

    @Parameter(description = "normal argument")
    List<String> args;

    TestCommand() {}

    @Override
    public void run() {
      ImmutableList.Builder<String> callRecord = new ImmutableList.Builder<>();
      callRecord.add(xparam);
      if (args != null) {
        callRecord.addAll(args);
      }
      commandInvocations.add(callRecord.build());
    }
  }

  @Parameters(commandDescription = "Another test command")
  static class TestAnotherCommand implements Command {
    @Override
    public void run() {}
  }
}
