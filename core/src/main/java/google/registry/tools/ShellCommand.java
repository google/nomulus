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

import static com.google.common.base.StandardSystemProperty.USER_HOME;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterDescription;
import com.beust.jcommander.Parameters;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import google.registry.util.Clock;
import google.registry.util.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;
import org.jline.builtins.Completers.FileNameCompleter;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Implements a tiny shell interpreter for the nomulus tool.
 *
 * <p>Parses a very simple command grammar. Tokens are either whitespace delimited words or
 * double-quoted strings.
 */
@Parameters(commandDescription = "Run an interactive shell")
public class ShellCommand implements Command {

  private static final String HISTORY_FILE = ".nomulus_history";
  private static final String RESET = "\u001b[0m";
  private static final String NON_ALERT_COLOR = "\u001b[32m"; // green foreground
  private static final String ALERT_COLOR = "\u001b[1;41;97m"; // red background
  private static final Duration IDLE_THRESHOLD = Duration.standardHours(1);
  private static final String SUCCESS = "SUCCESS";
  private static final String FAILURE = "FAILURE";
  private static final String RUNNING = "RUNNING";
  private static final Escaper STRING_ESCAPER =
      Escapers.builder()
          .addEscape('\\', "\\\\")
          .addEscape('"', "\\\"")
          .addEscape('\n', "\\n")
          .addEscape('\r', "\\r")
          .addEscape('\t', "\\t")
          .build();

  /**
   * The runner we received in the constructor.
   *
   * <p>We might want to update this runner based on flags (e.g. --encapsulate_output), but these
   * flags aren't available in the constructor, so we have to do it in the {@link #run} function.
   */
  private final CommandRunner originalRunner;

  private final LineReader lineReader;
  private final Clock clock;

  private String prompt = null;

  @Parameter(
      names = {"--dont_exit_on_idle"},
      description =
          """
              Prevents the shell from exiting on PROD after the 1 hour idle delay.
              Will instead warn you and require re-running the command.""")
  boolean dontExitOnIdle = false;

  @Parameter(
      names = {"--encapsulate_output"},
      description =
          """
              Encapsulate command standard output and error by combining the two streams to
              standard output and inserting a prefix ('out:' or 'err:') at the beginning of every
              line of normal output and adding a line consisting of either 'SUCCESS' or
              'FAILURE <exception-name> <error-message>' at the end of the output for a
              command, allowing the output to be easily parsed by wrapper scripts.""")
  boolean encapsulateOutput = false;

  ShellCommand(CommandRunner runner) throws IOException {
    this(TerminalBuilder.terminal(), new SystemClock(), runner);
    prompt = "nom > ";
    lineReader.variable(LineReader.HISTORY_FILE, Path.of(USER_HOME.value(), HISTORY_FILE));
  }

  ShellCommand(Terminal terminal, Clock clock, CommandRunner runner) {
    this.originalRunner = runner;
    this.lineReader = LineReaderBuilder.builder().terminal(terminal).build();
    this.clock = clock;
  }

  private void setPrompt(RegistryToolEnvironment environment, boolean alert) {
    // Do not set the prompt in tests.
    if (lineReader.getTerminal() instanceof DumbTerminal) {
      return;
    }
    prompt =
        alert
            ? String.format("nom@%s%s%s > ", ALERT_COLOR, environment, RESET)
            : String.format(
                "nom@%s%s%s > ", NON_ALERT_COLOR, Ascii.toLowerCase(environment.toString()), RESET);
  }

  public ShellCommand buildCompletions(JCommander jcommander) {
    ((LineReaderImpl) lineReader).setCompleter(new JCommanderCompleter(jcommander));
    return this;
  }

  private record OutputEncapsulator(CommandRunner runner) implements CommandRunner {

    /**
     * Emit a success command separator.
     *
     * <p>Dumps the last line of output prior to doing this.
     */
    private static void emitSuccess() {
      System.out.println(SUCCESS);
      System.out.flush();
    }

    /**
     * Emit a failure message obtained from the throwable.
     *
     * <p>Dumps the last line of output prior to doing this.
     */
    private static void emitFailure(Throwable e) {
      System.out.format(
          "%s %s %s\n", FAILURE, e.getClass().getName(), STRING_ESCAPER.escape(e.getMessage()));
      System.out.flush();
    }

    private static void emitArguments(String[] args) {
      System.out.print(RUNNING);
      Arrays.stream(args).forEach(arg -> System.out.format(" \"%s\"", STRING_ESCAPER.escape(arg)));
      System.out.println();
      System.out.flush();
    }

    private void encapsulatedRun(String[] args) throws Exception {
      PrintStream orgOut = System.out;
      PrintStream orgErr = System.err;
      PrintStream newOut = new PrintStream(new EncapsulatingOutputStream(System.out, "out: "));
      PrintStream newErr = new PrintStream(new EncapsulatingOutputStream(System.out, "err: "));
      try {
        System.setOut(newOut);
        System.setErr(newErr);
        runner.run(args);
      } finally {
        System.setOut(orgOut);
        System.setErr(orgErr);
      }
    }

    /** Run "func" with output encapsulation. */
    @Override
    public void run(String[] args) {

      try {
        emitArguments(args);
        encapsulatedRun(args);
        emitSuccess();
      } catch (Exception e) {
        emitFailure(e);
      }
    }
  }

  /** Run the shell until the user presses "Ctrl-D". */
  @Override
  public void run() {
    // Wrap standard output and error if requested. We have to do so here in run because the flags
    // haven't been processed in the constructor.
    CommandRunner runner =
        encapsulateOutput ? new OutputEncapsulator(originalRunner) : originalRunner;
    // On Production, we want to be extra careful - to prevent accidental use.
    boolean beExtraCareful = (RegistryToolEnvironment.get() == RegistryToolEnvironment.PRODUCTION);
    setPrompt(RegistryToolEnvironment.get(), beExtraCareful);
    String line;
    DateTime lastTime = clock.nowUtc();
    while (true) {
      try {
        line = lineReader.readLine(prompt);
      } catch (UserInterruptException | EndOfFileException e) {
        break;
      }
      // Make sure we're not idle for too long. Only relevant when we're "extra careful"
      if (!dontExitOnIdle
          && beExtraCareful
          && lastTime.plus(IDLE_THRESHOLD).isBefore(clock.nowUtc())) {
        throw new RuntimeException(
            """
                Been idle for too long, while in 'extra careful' mode.
                The last command was saved in history. Please rerun the shell and try again.""");
      }
      lastTime = clock.nowUtc();
      String[] lineArgs = parseCommand(line);
      if (lineArgs.length == 0) {
        continue;
      } else if (lineArgs.length == 1 && "exit".equals(lineArgs[0])) {
        break;
      }
      try {
        runner.run(lineArgs);
      } catch (Exception e) {
        System.err.println("Got an exception:\n" + e);
        e.printStackTrace();
      }
    }
    if (!encapsulateOutput) {
      System.err.println();
    }
  }

  @VisibleForTesting
  static String[] parseCommand(String line) {
    ImmutableList.Builder<String> resultBuilder = new ImmutableList.Builder<>();

    // Create a tokenizer, make everything word characters except quoted strings and unprintable
    // ascii chars and space (just treat them all as whitespace).
    StreamTokenizer tokenizer = new StreamTokenizer(new StringReader(line));
    tokenizer.resetSyntax();
    tokenizer.whitespaceChars(0, ' ');
    tokenizer.wordChars('!', '~');
    tokenizer.quoteChar('"');

    try {
      while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
        resultBuilder.add(tokenizer.sval);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return resultBuilder.build().toArray(new String[0]);
  }

  static class JCommanderCompleter implements Completer {

    private static final ParamDoc DEFAULT_PARAM_DOC =
        new ParamDoc("[No documentation available]", ImmutableList.of());

    /**
     * Documentation for all the known command + argument combinations.
     *
     * <p>For every command, has documentation for all flags and for the "main" parameters (the ones
     * that don't have a flag)
     *
     * <p>The order is: row is the command, col is the flag.
     *
     * <p>The flag documentations are keyed to the full flag - including any dashes (so "--flag"
     * rather than "flag").
     *
     * <p>The "main" parameter documentation is keyed to "". Every command has documentation for the
     * main parameters, even if it doesn't accept any (if it doesn't accept any, the documentation
     * is "no documentation available"). That means every command has at least one full table cell
     * (the "" key, for the main parameter). THIS IS IMPORTANT - otherwise the command won't appear
     * in {@link ImmutableTable#rowKeySet}.
     */
    private final ImmutableTable<String, String, ParamDoc> commandFlagDocs;

    private final FileNameCompleter filenameCompleter = new FileNameCompleter();

    /**
     * Holds all the information about a parameter we need for completion.
     *
     * <p>Parameters include the values after flags, and the "main parameters" that don't have a
     * flag associated with them.
     *
     * <p>The information includes documentation and all the possible options, if known.
     *
     * <p>For now - "all possible options" are only known for enum parameters.
     */
    record ParamDoc(String documentation, ImmutableList<String> options) {

      static ParamDoc create(@Nullable ParameterDescription parameter) {
        if (parameter == null) {
          return new ParamDoc("[None]", ImmutableList.of());
        }
        String type = parameter.getParameterized().getGenericType().toString();
        Class<?> clazz = parameter.getParameterized().getType();
        ImmutableList<String> options = ImmutableList.of();
        if (clazz.isEnum()) {
          options =
              Arrays.stream(clazz.getEnumConstants())
                  .map(Object::toString)
                  .collect(toImmutableList());
          type = String.join(", ", options);
        }
        if (type.startsWith("class ")) {
          type = type.substring(6);
        }
        return new ParamDoc(String.format("%s\n  (%s)", parameter.getDescription(), type), options);
      }
    }

    /**
     * Populates the completions and documentation based on the JCommander.
     *
     * <p>The input data is copied, so changing the jcommander after creation of the
     * JCommanderCompleter doesn't change the completions.
     */
    JCommanderCompleter(JCommander jcommander) {
      ImmutableTable.Builder<String, String, ParamDoc> builder = new ImmutableTable.Builder<>();

      // Go over all the commands
      for (Entry<String, JCommander> entry : jcommander.getCommands().entrySet()) {
        String command = entry.getKey();
        JCommander subCommander = entry.getValue();

        // Add the "main" parameters documentation
        builder.put(
            command,
            "",
            ParamDoc.create(
                subCommander.getMainParameter() == null
                    ? null
                    : subCommander.getMainParameterValue()));

        // For each command - go over the parameters (arguments / flags)
        for (ParameterDescription parameter : subCommander.getParameters()) {
          ParamDoc paramDoc = ParamDoc.create(parameter);

          // For each parameter - go over all the "flag" names of that parameter (e.g., -o and
          // --output being aliases of the same parameter) and populate each one
          Arrays.stream(parameter.getParameter().names())
              .forEach(flag -> builder.put(command, flag, paramDoc));
        }
      }
      commandFlagDocs = builder.build();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
      int argumentIndex = line.wordIndex();
      // The argument we want to complete (only partially written, might even be empty)
      String partialArgument = line.word();
      // The command name. Null if we're at the first argument
      String command = argumentIndex == 0 ? null : line.words().getFirst();
      // The previous argument before it - used for context. Null if we're at the first argument
      String previousArgument = argumentIndex <= 1 ? null : line.words().get(argumentIndex - 1);

      // If it's obviously a file path (starts with something "file path like") - complete as a file
      if (partialArgument.startsWith("./")
          || partialArgument.startsWith("~/")
          || partialArgument.startsWith("/")) {
        filenameCompleter.complete(reader, line, candidates);
      }

      // Complete based on flag data
      getCompletions(command, previousArgument, partialArgument).stream()
          .map(Candidate::new)
          .forEach(candidates::add);
    }

    /**
     * Completes a (partial) word based on the command and context.
     *
     * @param command the name of the command we're running. Null if not yet known (it is in 'word')
     * @param context the previous argument for context. Null if we're the first.
     * @param word the (partial) word to complete. Can be the command if "command" is null, or any
     *     "regular" argument, if "command" isn't null.
     * @return list of all possible completions to 'word'
     */
    private List<String> getCompletions(
        @Nullable String command, @Nullable String context, String word) {

      // Complete the first argument based on the jcommander commands
      if (command == null) {
        return getCommandCompletions(word);
      }

      // For the "help" command, complete the second argument based on the jcommander commands, and
      // the rest of the arguments fail to complete
      if (command.equals("help")) {
        // "help" only has completion for the first argument
        if (context != null) {
          return ImmutableList.of();
        }
        return getCommandCompletions(word);
      }

      // If it's the beginning of a flag, complete the flag
      if (word.startsWith("-")) {
        return getFlagCompletions(command, word);
      }

      // 'tab' on empty will show the documentation, while 'tab' on non-empty will attempt to
      // complete if we know how (currently - only on enums)
      // - either for the "current flag" or for the main
      // parameters, depending on the context (the "context" being the previous argument)
      return getParameterDocCompletions(command, context, word);
    }

    private List<String> getCommandCompletions(String word) {
      return commandFlagDocs.rowKeySet().stream()
          .filter(s -> s.startsWith(word))
          .map(s -> s + " ")
          .collect(toImmutableList());
    }

    private List<String> getFlagCompletions(String command, String word) {
      return commandFlagDocs.row(command).keySet().stream()
          .filter(s -> s.startsWith(word))
          .map(s -> s + " ")
          .collect(toImmutableList());
    }

    private List<String> getParameterDocCompletions(
        String command, @Nullable String context, String word) {
      // First, check if we want the documentation for a specific flag, or for the "main"
      // parameters.
      //
      // We want documentation for a flag if the previous argument was a flag, but the value of the
      // flag wasn't set. So if the previous argument is "--flag" then we want documentation of that
      // flag, but if it's "--flag=value" then that flag is set, and we want documentation of the
      // main parameters.
      boolean isFlagParameter =
          context != null && context.startsWith("-") && context.indexOf('=') == -1;
      ParamDoc paramDoc =
          Optional.ofNullable(commandFlagDocs.get(command, isFlagParameter ? context : ""))
              .orElse(DEFAULT_PARAM_DOC);
      if (!word.isEmpty()) {
        return paramDoc.options().stream()
            .filter(s -> s.startsWith(word))
            .map(s -> s + " ")
            .collect(toImmutableList());
      }
      String documentation =
          String.format(
              "%s: %s",
              isFlagParameter ? "Flag documentation" : "Main parameter", paramDoc.documentation());
      return ImmutableList.of("", documentation);
    }
  }

  /**
   * Encapsulate output according to the protocol described in the documentation for the
   * --encapsulate_output flag.
   */
  @VisibleForTesting
  static class EncapsulatingOutputStream extends FilterOutputStream {

    private final byte[] prefix;
    private final ByteArrayOutputStream lastLine = new ByteArrayOutputStream();

    EncapsulatingOutputStream(OutputStream out, String identifier) {
      super(out);
      this.prefix = identifier.getBytes(UTF_8);
    }

    @Override
    public void write(int b) throws IOException {
      lastLine.write(b);
      if (b == '\n') {
        out.write(prefix);
        lastLine.writeTo(out);
        out.flush();
        lastLine.reset();
      }
    }

    @Override
    public void flush() throws IOException {
      dumpLastLine();
      super.flush();
    }

    @Override
    public void close() throws IOException {
      dumpLastLine();
      // We do NOT want to call super.close as that would close the original outputStream
      // (System.out)
    }

    /** Dump the accumulated last line of output if there was one. */
    public void dumpLastLine() throws IOException {
      if (lastLine.size() > 0) {
        out.write(prefix);
        lastLine.writeTo(out);
        out.write('\n');
        out.flush();
        lastLine.reset();
      }
    }
  }
}
