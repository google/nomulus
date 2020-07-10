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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.SystemInfo.hasCommand;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import google.registry.keyring.api.Keyring;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.GpgSystemCommandRule;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.stream.Stream;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** GnuPG integration tests for {@link Ghostryde}. */
class GhostrydeGpgIntegrationTest {

  @RegisterExtension public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @RegisterExtension
  public final GpgSystemCommandRule gpg =
      new GpgSystemCommandRule(
          RdeTestData.loadBytes("pgp-public-keyring.asc"),
          RdeTestData.loadBytes("pgp-private-keyring-registry.asc"));

  private static final ImmutableList<String> COMMANDS = ImmutableList.of("gpg", "gpg2");
  private static final ImmutableList<String> CONTENTS =
      ImmutableList.of(
          "(◕‿◕)",
          Strings.repeat("Fanatics have their dreams, wherewith they weave\n", 1000),
          "\0yolo",
          "");

  private static Stream<Arguments> provideTestCombinations() {
    Stream.Builder<Arguments> stream = Stream.builder();
    for (String command : COMMANDS) {
      for (String content : CONTENTS) {
        stream.add(Arguments.of(command, content));
      }
    }
    return stream.build();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void test(String command, String content) throws Exception {
    assumeTrue(hasCommand(command + " --version"));
    Keyring keyring = new FakeKeyringModule().get();
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();
    File file = new File(gpg.getCwd(), "love.gpg");
    byte[] data = content.getBytes(UTF_8);

    try (OutputStream output = new FileOutputStream(file);
        OutputStream ghostrydeEncoder = Ghostryde.encoder(output, publicKey)) {
      ghostrydeEncoder.write(data);
    }

    Process pid = gpg.exec(command, "--list-packets", "--keyid-format", "long", file.getPath());
    String stdout = CharStreams.toString(new InputStreamReader(pid.getInputStream(), UTF_8));
    String stderr = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertThat(stdout).contains(":compressed packet:");
    assertThat(stdout).contains(":encrypted data packet:");
    assertThat(stdout).contains("version 3, algo 1, keyid A59C132F3589A1D5");
    assertThat(stdout).contains("name=\"" + Ghostryde.INNER_FILENAME + "\"");
    assertThat(stderr).contains("encrypted with 2048-bit RSA key, ID A59C132F3589A1D5");

    pid = gpg.exec(command, "--use-embedded-filename", file.getPath());
    stderr = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    File dataFile = new File(gpg.getCwd(), Ghostryde.INNER_FILENAME);
    assertThat(dataFile.exists()).isTrue();
    assertThat(slurp(dataFile)).isEqualTo(content);
  }

  private String slurp(File file) throws IOException {
    return CharStreams.toString(new InputStreamReader(new FileInputStream(file), UTF_8));
  }
}
