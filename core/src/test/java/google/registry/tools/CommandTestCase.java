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

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.toArray;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.JCommander;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ObjectArrays;
import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;
import google.registry.model.poll.PollMessage;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CertificateSamples;
import google.registry.testing.FakeClock;
import google.registry.testing.SystemPropertyExtension;
import google.registry.tools.params.ParameterFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Base class for all command tests.
 *
 * @param <C> the command type
 */
@ExtendWith(MockitoExtension.class)
public abstract class CommandTestCase<C extends Command> {

  // Lock for stdout/stderr.  Note that this is static: since we're dealing with globals, we need
  // to lock for the entire JVM.
  private static final ReentrantLock streamsLock = new ReentrantLock();

  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private PrintStream oldStdout, oldStderr;

  protected C command;

  protected final FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(fakeClock)
          .withTaskQueue()
          .build();

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @TempDir public Path tmpDir;

  @BeforeEach
  public final void beforeEachCommandTestCase() throws Exception {
    // Ensure the UNITTEST environment has been set before constructing a new command instance.
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
    command = newCommandInstance();

    // Capture standard output/error. This is problematic because gradle tests run in parallel in
    // the same JVM.  So first lock out any other tests in this JVM that are trying to do this
    // trick.
    streamsLock.lock();
    oldStdout = System.out;
    System.setOut(new PrintStream(new OutputSplitter(System.out, stdout)));
    oldStderr = System.err;
    System.setErr(new PrintStream(new OutputSplitter(System.err, stderr)));
  }

  @AfterEach
  public final void afterEachCommandTestCase() {
    System.setOut(oldStdout);
    System.setErr(oldStderr);
    streamsLock.unlock();
  }

  void runCommandInEnvironment(RegistryToolEnvironment env, String... args) throws Exception {
    env.setup(systemPropertyExtension);
    try {
      JCommander jcommander = new JCommander(command);
      jcommander.addConverterFactory(new ParameterFactory());
      jcommander.parse(args);
      command.run();
    } finally {
      // Clear the session cache so that subsequent reads for verification purposes hit Datastore.
      // This primarily matters for AutoTimestamp fields, which otherwise won't have updated values.
      tm().clearSessionCache();
      // Reset back to UNITTEST environment.
      RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
    }
  }

  protected void runCommand(String... args) throws Exception {
    runCommandInEnvironment(RegistryToolEnvironment.UNITTEST, args);
  }

  protected void runCommand(Iterable<String> args) throws Exception {
    runCommandInEnvironment(
        RegistryToolEnvironment.UNITTEST, Iterables.toArray(args, String.class));
  }

  /** Adds "--force" as the first parameter, then runs the command. */
  protected void runCommandForced(String... args) throws Exception {
    runCommand(ObjectArrays.concat("--force", args));
  }

  /** Adds "--force" as the first parameter, then runs the command. */
  protected void runCommandForced(Iterable<String> args) throws Exception {
    runCommand(concat(ImmutableList.of("--force"), args));
  }

  /** Writes the data to a named temporary file and then returns a path to the file. */
  private String writeToNamedTmpFile(String filename, byte[] data) throws IOException {
    Path tmpFile = tmpDir.resolve(filename);
    Files.write(data, tmpFile.toFile());
    return tmpFile.toString();
  }

  /** Writes the data to a named temporary file and then returns a path to the file. */
  String writeToNamedTmpFile(String filename, String...data) throws IOException {
    return writeToNamedTmpFile(filename, Joiner.on('\n').join(data).getBytes(UTF_8));
  }

  /** Writes the data to a temporary file and then returns a path to the file. */
  String writeToNamedTmpFile(String filename, Iterable<String> data) throws IOException {
    return writeToNamedTmpFile(filename, toArray(data, String.class));
  }

  /** Writes the data to a temporary file and then returns a path to the file. */
  String writeToTmpFile(byte[] data) throws IOException {
    return writeToNamedTmpFile("tmp_file", data);
  }

  /** Writes the data to a temporary file and then returns a path to the file. */
  String writeToTmpFile(String...data) throws IOException {
    return writeToNamedTmpFile("tmp_file", data);
  }

  /** Writes the data to a temporary file and then returns a path to the file. */
  String writeToTmpFile(Iterable<String> data) throws IOException {
    return writeToNamedTmpFile("tmp_file", toArray(data, String.class));
  }

  /** Returns a path to a known good certificate file. */
  String getCertFilename() throws IOException {
    return getCertFilename(CertificateSamples.SAMPLE_CERT);
  }

  /** Returns a path to a specified certificate file. */
  String getCertFilename(String certificateFile) throws IOException {
    return writeToNamedTmpFile("cert.pem", certificateFile);
  }

  /** Reloads the given resource from Datastore. */
  <T> T reloadResource(T resource) {
    return transactIfJpaTm(() -> tm().loadByEntity(resource));
  }

  /** Returns count of all poll messages in Datastore. */
  int getPollMessageCount() {
    return transactIfJpaTm(() -> tm().loadAllOf(PollMessage.class).size());
  }

  /**
   * Asserts whether standard out matches an expected string, allowing for differences in
   * ImmutableObject hash codes (i.e. "(@1234567)").
   */
  protected void assertStdoutForImmutableObjectIs(String expected) {
    assertThat(stripImmutableObjectHashCodes(getStdoutAsString()).trim())
        .isEqualTo(stripImmutableObjectHashCodes(expected).trim());
  }

  void assertStdoutIs(String expected) {
    assertThat(getStdoutAsString()).isEqualTo(expected);
  }

  protected void assertInStdout(String... expected) {
    String stdout = getStdoutAsString();
    for (String line : expected) {
      assertThat(stdout).contains(line);
    }
  }

  void assertInStderr(String... expected) {
    String stderror = new String(stderr.toByteArray(), UTF_8);
    for (String line : expected) {
      assertThat(stderror).contains(line);
    }
  }

  void assertNotInStdout(String expected) {
    assertThat(getStdoutAsString()).doesNotContain(expected);
  }

  void assertNotInStderr(String expected) {
    assertThat(getStderrAsString()).doesNotContain(expected);
  }

  String getStdoutAsString() {
    return new String(stdout.toByteArray(), UTF_8);
  }

  String getStderrAsString() {
    return new String(stderr.toByteArray(), UTF_8);
  }

  List<String> getStdoutAsLines() {
    return Splitter.on('\n').omitEmptyStrings().trimResults().splitToList(getStdoutAsString());
  }

  private String stripImmutableObjectHashCodes(String string) {
    return string.replaceAll("\\(@\\d+\\)", "(@)");
  }

  @SuppressWarnings("unchecked")
  protected C newCommandInstance() throws Exception {
    try {
      return (C)
          new TypeToken<C>(getClass()) {}.getRawType().getDeclaredConstructor().newInstance();
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Splits an output stream, writing it to two other output streams.
   *
   * <p>We use this as a replacement for standard out/error so that we can both capture the output
   * of the command and display it to the console for debugging.
   */
  static class OutputSplitter extends OutputStream {

    OutputStream a, b;

    OutputSplitter(OutputStream a, OutputStream b) {
      this.a = a;
      this.b = b;
    }

    @Override
    public void write(byte[] data) throws IOException {
      a.write(data);
      b.write(data);
    }

    @Override
    public void write(byte[] data, int off, int len) throws IOException {
      a.write(data, off, len);
      b.write(data, off, len);
    }

    @Override
    public void close() throws IOException {
      a.close();
      b.close();
    }

    @Override
    public void flush() throws IOException {
      a.flush();
      b.flush();
    }

    @Override
    public void write(int val) throws IOException {
      a.write(val);
      b.write(val);
    }
  }
}
