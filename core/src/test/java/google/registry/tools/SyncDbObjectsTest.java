// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.util.BuildPathUtils.getProjectRoot;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@code release/db-object-updater/sync_db_objects.sh}. */
class SyncDbObjectsTest {

  private static final Path SCRIPT_PATH =
      getProjectRoot().resolve("release/db-object-updater/sync_db_objects.sh");

  @TempDir Path tmpDir;

  private Path fakeBinDir;
  private Path invocationsLog;
  private Path configDir;
  private Path envDir;
  private Path credentialFile;

  @BeforeEach
  void beforeEach() throws IOException {
    fakeBinDir = Files.createDirectories(tmpDir.resolve("bin"));
    invocationsLog = tmpDir.resolve("invocations.log");
    configDir = Files.createDirectories(tmpDir.resolve("config"));
    envDir = configDir.resolve("alpha");
    credentialFile = tmpDir.resolve("nomulus_tool_credential.json");
    Files.writeString(credentialFile, "{}", UTF_8);
    installFakeJava("exit 0");
  }

  private void installFakeJava(String exitLogic) throws IOException {
    Path fakeJava = fakeBinDir.resolve("java");
    String script =
        String.join(
            "\n", "#!/bin/bash", "echo \"$*\" >> \"" + invocationsLog + "\"", exitLogic, "");
    Files.writeString(fakeJava, script, UTF_8);
    Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private ScriptResult runScript(String... args) throws IOException, InterruptedException {
    ImmutableList<String> command =
        ImmutableList.<String>builder().add("/bin/bash", SCRIPT_PATH.toString()).add(args).build();
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    String currentPath = System.getenv("PATH");
    pb.environment()
        .put("PATH", fakeBinDir + (currentPath != null ? File.pathSeparator + currentPath : ""));
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    int exitCode = process.waitFor();
    ImmutableList<String> invocations =
        Files.exists(invocationsLog)
            ? ImmutableList.copyOf(Files.readAllLines(invocationsLog, UTF_8))
            : ImmutableList.of();
    return new ScriptResult(exitCode, output, invocations);
  }

  @Test
  void testFailure_tooFewArguments() throws Exception {
    ScriptResult result = runScript("alpha", credentialFile.toString(), "configure_tld");
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output())
        .contains(
            "Expecting four parameters in order: "
                + "env tools_credential nomulus_command config_file_directory");
    assertThat(result.invocations()).isEmpty();
  }

  @Test
  void testFailure_tooManyArguments() throws Exception {
    ScriptResult result =
        runScript(
            "alpha", credentialFile.toString(), "configure_tld", configDir.toString(), "extra_arg");
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.output())
        .contains(
            "Expecting four parameters in order: "
                + "env tools_credential nomulus_command config_file_directory");
    assertThat(result.invocations()).isEmpty();
  }

  @Test
  void testSuccess_configureTld_doesNotPassUpsert() throws Exception {
    Files.createDirectories(envDir);
    Path tldFile = envDir.resolve("example.yaml");
    Files.writeString(tldFile, "tldStr: example\n", UTF_8);

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "configure_tld", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " configure_tld -i "
                + tldFile
                + " --force --build_environment");
  }

  @Test
  void testSuccess_updatePremiumList_passesUpsert() throws Exception {
    Files.createDirectories(envDir);
    Path premiumFile = envDir.resolve("example.txt");
    Files.writeString(premiumFile, "foo,USD 100\n", UTF_8);

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_premium_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_premium_list -i "
                + premiumFile
                + " --force --build_environment --upsert");
  }

  @Test
  void testSuccess_updateReservedList_passesUpsert() throws Exception {
    Files.createDirectories(envDir);
    Path reservedFile = envDir.resolve("common_reserved.txt");
    Files.writeString(reservedFile, "baddies,FULLY_BLOCKED\n", UTF_8);

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_reserved_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_reserved_list -i "
                + reservedFile
                + " --force --build_environment --upsert");
  }

  @Test
  void testFailure_configureTldFails_exitsNonZeroImmediately() throws Exception {
    Files.createDirectories(envDir);
    Path tldFileA = envDir.resolve("a.yaml");
    Path tldFileB = envDir.resolve("b.yaml");
    Files.writeString(tldFileA, "tldStr: a\n", UTF_8);
    Files.writeString(tldFileB, "tldStr: b\n", UTF_8);
    installFakeJava("exit 1");

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "configure_tld", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " configure_tld -i "
                + tldFileA
                + " --force --build_environment");
  }

  @Test
  void testFailure_updatePremiumListFails_exitsNonZeroImmediately() throws Exception {
    Files.createDirectories(envDir);
    Path fileA = envDir.resolve("a.txt");
    Path fileB = envDir.resolve("b.txt");
    Files.writeString(fileA, "foo,USD 10\n", UTF_8);
    Files.writeString(fileB, "bar,USD 20\n", UTF_8);
    installFakeJava("exit 1");

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_premium_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_premium_list -i "
                + fileA
                + " --force --build_environment --upsert");
  }

  @Test
  void testFailure_updateReservedListFails_exitsNonZeroImmediately() throws Exception {
    Files.createDirectories(envDir);
    Path fileA = envDir.resolve("a.txt");
    Path fileB = envDir.resolve("b.txt");
    Files.writeString(fileA, "foo,FULLY_BLOCKED\n", UTF_8);
    Files.writeString(fileB, "bar,FULLY_BLOCKED\n", UTF_8);
    installFakeJava("exit 1");

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_reserved_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_reserved_list -i "
                + fileA
                + " --force --build_environment --upsert");
  }

  @Test
  void testSuccess_emptyEnvironmentDirectory() throws Exception {
    Files.createDirectories(envDir);

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_premium_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations()).isEmpty();
  }

  @Test
  void testSuccess_missingEnvironmentDirectory() throws Exception {
    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_premium_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations()).isEmpty();
  }

  @Test
  void testSuccess_directoryPathWithSpaces() throws Exception {
    Path spacedConfigDir = Files.createDirectories(tmpDir.resolve("config with spaces"));
    Path spacedEnvDir = Files.createDirectories(spacedConfigDir.resolve("alpha"));
    Path premiumFile = spacedEnvDir.resolve("cad list.txt");
    Files.writeString(premiumFile, "maple,CAD 50\n", UTF_8);

    ScriptResult result =
        runScript(
            "alpha", credentialFile.toString(), "update_premium_list", spacedConfigDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_premium_list -i "
                + premiumFile
                + " --force --build_environment --upsert");
  }

  @Test
  void testSuccess_multipleFiles_processedInOrder() throws Exception {
    Files.createDirectories(envDir);
    Path firstFile = envDir.resolve("a_first.txt");
    Path secondFile = envDir.resolve("b_second.txt");
    Files.writeString(firstFile, "foo,USD 10\n", UTF_8);
    Files.writeString(secondFile, "bar,GBP 20\n", UTF_8);

    ScriptResult result =
        runScript("alpha", credentialFile.toString(), "update_premium_list", configDir.toString());
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.invocations())
        .containsExactly(
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_premium_list -i "
                + firstFile
                + " --force --build_environment --upsert",
            "-jar /nomulus.jar -e alpha --credential "
                + credentialFile
                + " update_premium_list -i "
                + secondFile
                + " --force --build_environment --upsert")
        .inOrder();
  }

  private record ScriptResult(int exitCode, String output, ImmutableList<String> invocations) {}
}
