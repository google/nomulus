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
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import google.registry.keyring.api.Keyring;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.GpgSystemCommandRule;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.stream.Stream;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** GPG combinatorial integration tests for the Ryde classes. */
public class RydeGpgIntegrationTest {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @RegisterExtension public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @RegisterExtension
  public final GpgSystemCommandRule gpg =
      new GpgSystemCommandRule(
          RdeTestData.loadBytes("pgp-public-keyring.asc"),
          RdeTestData.loadBytes("pgp-private-keyring-escrow.asc"));

  private final FakeKeyringModule keyringFactory = new FakeKeyringModule();

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
    final String filename = "sloth";
    assumeTrue(hasCommand("tar"));
    assumeTrue(hasCommand(command + " --version"));

    Keyring keyring = keyringFactory.get();
    PGPKeyPair signingKey = keyring.getRdeSigningKey();
    PGPPublicKey receiverKey = keyring.getRdeReceiverKey();
    DateTime modified = DateTime.parse("1984-01-01T00:00:00Z");
    File home = gpg.getCwd();
    File rydeFile = new File(home, filename + ".ryde");
    File sigFile = new File(home, filename + ".sig");
    File tarFile = new File(home, filename + ".tar");
    File xmlFile = new File(home, filename + ".xml");
    byte[] data = content.getBytes(UTF_8);

    try (OutputStream rydeOut = new FileOutputStream(rydeFile);
        OutputStream sigOut = new FileOutputStream(sigFile);
        RydeEncoder rydeEncoder =
            new RydeEncoder.Builder()
                .setRydeOutput(rydeOut, receiverKey)
                .setSignatureOutput(sigOut, signingKey)
                .setFileMetadata(filename, data.length, modified)
                .build()) {
      rydeEncoder.write(data);
    }

    // Iron Mountain examines the ryde file to see what sort of OpenPGP layers it contains.
    //
    // :pubkey enc packet: version 3, algo 1, keyid 239F455A2ACEE5C2
    //         data: [2047 bits]
    // :encrypted data packet:
    //         length: 2005
    // gpg: encrypted with 2048-bit RSA key, ID 54E1EB0F, created 2015-04-07
    //      "Marla Singer <rde-unittest@escrow.test>"
    // :compressed packet: algo=1
    // :literal data packet:
    //         mode b (62), created 1287273600, name="lol_2010-10-17_full_S1_R0.tar",
    //         raw data: 10752 bytes
    // gpg: WARNING: message was not integrity protected
    logger.atInfo().log("Running GPG to list info about OpenPGP message...");
    {
      Process pid =
          gpg.exec(
              command,
              "--list-packets",
              "--ignore-mdc-error",
              "--keyid-format",
              "long",
              rydeFile.toString());
      String stdout = slurp(pid.getInputStream());
      String stderr = slurp(pid.getErrorStream());
      assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
      assertWithMessage("OpenPGP message is missing encryption layer")
          .that(stdout)
          .contains(":pubkey enc packet:");
      assertWithMessage("Unexpected symmetric encryption algorithm")
          .that(stdout)
          .contains(":pubkey enc packet: version 3, algo 1");
      assertWithMessage("OpenPGP message is missing compression layer")
          .that(stdout)
          .contains(":compressed packet:");
      assertWithMessage("Expected zip compression algorithm")
          .that(stdout)
          .contains(":compressed packet: algo=1");
      assertWithMessage("OpenPGP message is missing literal data packet")
          .that(stdout)
          .contains(":literal data packet:");
      assertWithMessage("Literal data packet does not contain correct filename")
          .that(stdout)
          .contains("name=\"" + filename + ".tar\"");
      assertWithMessage("Literal data packet should be in BINARY mode")
          .that(stdout)
          .contains("mode b ");
      assertWithMessage("Unexpected asymmetric encryption algorithm")
          .that(stderr)
          .contains("encrypted with 2048-bit RSA key");
      assertWithMessage("Unexpected receiver public key")
          .that(stderr)
          .contains("ID 7F9084EE54E1EB0F");
    }

    // Iron Mountain now verifies that rydeFile is authentic and was signed appropriately.
    //
    // jart@jart:/tmp$ gpg --verify /tmp/deposit.sig /tmp/deposit.ryde
    // gpg: Signature made Mon 26 Aug 2013 12:04:27 PM EDT using RSA-S key ID 2774D88E
    // gpg: Good signature from <rde-unittest@registry.test>
    logger.atInfo().log("Running GPG to verify signature...");
    {
      Process pid = gpg.exec(command, "--verify", sigFile.toString(), rydeFile.toString());
      String stderr = slurp(pid.getErrorStream());
      assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
      assertThat(stderr).contains("Good signature");
      assertThat(stderr).contains("rde-unittest@registry.test");
    }

    // Iron Mountain now decrypts the ryde file to produce a tar file.
    //
    // jart@jart:/tmp$ gpg -v --use-embedded-filename /tmp/deposit.ryde
    // gpg: public key is 2ACEE5C2
    // gpg: encrypted with 2048-bit RSA key, ID 54E1EB0F, created 2015-04-07
    //      "Marla Singer <rde-unittest@escrow.test>"
    // gpg: AES encrypted data
    // gpg: original file name='lol_2010-10-17_full_S1_R0.tar'
    // gpg: WARNING: message was not integrity protected
    logger.atInfo().log("Running GPG to extract tar...");
    {
      Process pid =
          gpg.exec(command, "--use-embedded-filename", "--ignore-mdc-error", rydeFile.toString());
      String stderr = slurp(pid.getErrorStream());
      assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    }
    assertWithMessage("gpg decrypt did not produce expected tar file")
        .that(tarFile.exists())
        .isTrue();

    // ...and finally, Iron Mountain extracts the tar file to get a happy XML file ^__^
    logger.atInfo().log("Running GNU tar to extract content...");
    {
      Process pid = gpg.exec("tar", "-xf", tarFile.toString());
      String stderr = slurp(pid.getErrorStream());
      assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    }
    assertWithMessage("tar did not produce expected xml file").that(xmlFile.exists()).isTrue();
    assertThat(slurp(xmlFile)).isEqualTo(content);
  }

  private String slurp(File file) throws IOException {
    return CharStreams.toString(new InputStreamReader(new FileInputStream(file), UTF_8));
  }

  private String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
