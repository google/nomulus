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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.MissingCommandException;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.FakeClock;
import google.registry.testing.SystemPropertyExtension;
import google.registry.tools.ShellCommand.JCommanderCompleter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ShellCommand}. */
class ShellCommandTest {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  CommandRunner cli = mock(CommandRunner.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  private final DelayingByteArrayInputStream input = new DelayingByteArrayInputStream(clock);

  private PrintStream orgStdout;
  private PrintStream orgStderr;

  private ByteArrayOutputStream stdout;
  private ByteArrayOutputStream stderr;

  @BeforeEach
  void beforeEach() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
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
    input.setInput(commands);
    input.setDelay(delay);
    Terminal terminal = new DumbTerminal(input, System.out);
    return new ShellCommand(terminal, clock, commandRunner);
  }

  @Test
  void testCommandProcessing() throws Exception {
    FakeCli cli = new FakeCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.ZERO, "test1 foo bar", "test2 foo bar", "exit");
    shellCommand.run();
    assertThat(cli.calls)
        // "exit" causes the shell to exit and does not call cli.run.
        .containsExactly(
            ImmutableList.of("test1", "foo", "bar"), ImmutableList.of("test2", "foo", "bar"))
        .inOrder();
  }

  @Test
  void testNoIdleWhenInAlpha() throws Exception {
    RegistryToolEnvironment.ALPHA.setup(systemPropertyExtension);
    FakeCli cli = new FakeCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  void testNoIdleWhenInSandbox() throws Exception {
    RegistryToolEnvironment.SANDBOX.setup(systemPropertyExtension);
    FakeCli cli = new FakeCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  void testIdleWhenOverHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup(systemPropertyExtension);
    FakeCli cli = new FakeCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(61), "test1 foo bar", "test2 foo bar");
    RuntimeException exception = assertThrows(RuntimeException.class, shellCommand::run);
    assertThat(exception).hasMessageThat().contains("Been idle for too long");
  }

  @Test
  void testNoIdleWhenUnderHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup(systemPropertyExtension);
    FakeCli cli = new FakeCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(59), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  void testMultipleCommandInvocations() throws Exception {
    RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class));
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

  @Test
  void testNonExistentCommand() {
    RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class));

    cli.setEnvironment(RegistryToolEnvironment.UNITTEST);
    assertThrows(MissingCommandException.class, () -> cli.run(new String[] {"bad_command"}));
  }

  private void performJCommanderCompletorTest(String line, String... expectedCompletions) {
    JCommander jcommander = new JCommander();
    List<Candidate> candidates = Arrays.stream(expectedCompletions).map(Candidate::new).toList();
    jcommander.setProgramName("test");
    jcommander.addCommand("help", new HelpCommand(jcommander));
    jcommander.addCommand("testCommand", new TestCommand());
    jcommander.addCommand("testAnotherCommand", new TestAnotherCommand());
    List<Candidate> completions = new ArrayList<>();
    new JCommanderCompleter(jcommander)
        .complete(
            LineReaderBuilder.builder().build(),
            new DefaultParser().parse(line, line.length()),
            completions);
    assertThat(completions).containsExactlyElementsIn(candidates);
  }

  @Test
  void testCompletion_commands() {
    performJCommanderCompletorTest("", "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("n");
    performJCommanderCompletorTest("test", "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest(" test", "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest("testC", "testCommand ");
    performJCommanderCompletorTest("testA", "testAnotherCommand ");
  }

  @Test
  void testCompletion_help() {
    performJCommanderCompletorTest("h", "help ");
    performJCommanderCompletorTest("help ", "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("help testC", "testCommand ");
    performJCommanderCompletorTest("help testCommand ");
  }

  @Test
  void testCompletion_documentation() {
    performJCommanderCompletorTest(
        "testCommand ",
        "",
        "Main parameter: normal arguments\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand ", "", "Main parameter: [None]");
    performJCommanderCompletorTest(
        "testCommand -x ", "", "Flag documentation: test parameter\n  (java.lang.String)");
    performJCommanderCompletorTest(
        "testAnotherCommand -x ", "", "Flag documentation: [No documentation available]");
    performJCommanderCompletorTest(
        "testCommand x ",
        "",
        "Main parameter: normal arguments\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand x ", "", "Main parameter: [None]");
  }

  @Test
  void testCompletion_arguments() {
    performJCommanderCompletorTest("testCommand -", "-x ", "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testCommand --wrong");
    performJCommanderCompletorTest("testCommand noise  --", "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testAnotherCommand --o");
  }

  @Test
  void testCompletion_enum() {
    performJCommanderCompletorTest("testCommand --xorg P", "PRIVATE ", "PUBLIC ");
    performJCommanderCompletorTest("testCommand --xorg PU", "PUBLIC ");
    performJCommanderCompletorTest(
        "testCommand --xorg ", "", "Flag documentation: test organization\n  (PRIVATE, PUBLIC)");
  }

  @Test
  void testEncapsulatedOutputStream_basicFunctionality() throws Exception {
    ByteArrayOutputStream backing = new ByteArrayOutputStream();
    try (PrintStream out =
        new PrintStream(new ShellCommand.EncapsulatingOutputStream(backing, "out: "))) {
      out.println("first line");
      out.print("second line\ntrailing data");
    }
    assertThat(backing.toString(US_ASCII))
        .isEqualTo("out: first line\nout: second line\nout: trailing data\n");
  }

  @Test
  void testEncapsulatedOutputStream_emptyStream() throws Exception {
    ByteArrayOutputStream backing = new ByteArrayOutputStream();
    new PrintStream(new ShellCommand.EncapsulatingOutputStream(backing, "out: ")).close();
    assertThat(backing.toString(US_ASCII)).isEqualTo("");
  }

  @Test
  void testEncapsulatedOutput_command() throws Exception {
    captureOutput();
    ShellCommand shellCommand =
        createShellCommand(
            args -> {
              System.out.println("first line");
              System.err.println("second line");
              System.out.print("fragmented ");
              System.err.println("surprise!");
              System.out.println("line");
            },
            Duration.ZERO,
            "command1");
    shellCommand.encapsulateOutput = true;

    shellCommand.run();
    assertThat(stderr.toString(US_ASCII)).isEmpty();
    assertThat(stdout.toString(US_ASCII))
        .isEqualTo(
            """
                RUNNING "command1"
                out: first line
                err: second line
                err: surprise!
                out: fragmented line
                SUCCESS
                """);
  }

  @Test
  void testEncapsulatedOutput_throws() throws Exception {
    captureOutput();
    ShellCommand shellCommand =
        createShellCommand(
            args -> {
              System.out.println("first line");
              throw new Exception("some error!");
            },
            Duration.ZERO,
            "command1");
    shellCommand.encapsulateOutput = true;
    shellCommand.run();
    assertThat(stderr.toString(US_ASCII)).isEmpty();
    assertThat(stdout.toString(US_ASCII))
        .isEqualTo(
            """
                RUNNING "command1"
                out: first line
                FAILURE java.lang.Exception some error!
                """);
  }

  @Test
  void testEncapsulatedOutput_noCommand() throws Exception {
    captureOutput();
    ShellCommand shellCommand =
        createShellCommand(
            args -> System.out.println("first line"), Duration.ZERO, "", "do something");
    shellCommand.encapsulateOutput = true;
    shellCommand.run();
    assertThat(stderr.toString(US_ASCII)).isEmpty();
    assertThat(stdout.toString(US_ASCII))
        .isEqualTo("RUNNING \"do\" \"something\"\nout: first line\nSUCCESS\n");
  }

  void captureOutput() {
    // capture output (have to do this before the shell command is created)
    stdout = new ByteArrayOutputStream();
    stderr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(stdout));
    System.setErr(new PrintStream(stderr));
    System.setIn(new ByteArrayInputStream("command1\n".getBytes(US_ASCII)));
  }

  static class FakeCli implements CommandRunner {
    public ArrayList<ImmutableList<String>> calls = new ArrayList<>();

    @Override
    public void run(String[] args) {
      calls.add(ImmutableList.copyOf(args));
    }
  }

  @Parameters(commandDescription = "Test command")
  static class TestCommand implements Command {
    /**
     * List for recording command invocations by {@link #run}.
     *
     * <p>This has to be static because it gets populated by multiple TestCommand instances, which
     * are created in {@link RegistryCli} by using reflection to call the constructor.
     */
    static final List<List<String>> commandInvocations = new ArrayList<>();

    @Parameter(
        names = {"-x", "--xparam"},
        description = "test parameter")
    String xparam = "default value";

    @Parameter(
        names = {"--xorg"},
        description = "test organization")
    OrgType orgType = OrgType.PRIVATE;

    @Parameter(description = "normal arguments")
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

    enum OrgType {
      PRIVATE,
      PUBLIC
    }
  }

  @Parameters(commandDescription = "Another test command")
  static class TestAnotherCommand implements Command {
    @Override
    public void run() {}
  }

  @SuppressWarnings("InputStreamSlowMultibyteRead")
  private static class DelayingByteArrayInputStream extends InputStream {
    private final FakeClock clock;
    private ByteArrayInputStream stream;
    private Duration delay;

    DelayingByteArrayInputStream(FakeClock clock) {
      this.clock = clock;
    }

    void setInput(String... commands) {
      this.stream =
          new ByteArrayInputStream((String.join("\n", commands) + "\n").getBytes(US_ASCII));
    }

    void setDelay(Duration delay) {
      this.delay = delay;
    }

    @Override
    public int read() throws IOException {
      int nextByte = stream.read();
      if (nextByte == '\n') {
        clock.advanceBy(delay);
      }
      return nextByte;
    }
  }
}
