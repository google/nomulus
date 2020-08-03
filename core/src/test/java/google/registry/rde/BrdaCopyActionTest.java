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
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static google.registry.testing.SystemInfo.hasCommand;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.Keyring;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.GcsTestingUtils;
import google.registry.testing.GpgSystemCommandExtension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BrdaCopyAction}. */
public class BrdaCopyActionTest {

  private static final ByteSource DEPOSIT_XML = RdeTestData.loadBytes("deposit_full.xml");

  private static final GcsFilename STAGE_FILE =
      new GcsFilename("keg", "lol_2010-10-17_thin_S1_R0.xml.ghostryde");
  private static final GcsFilename STAGE_LENGTH_FILE =
      new GcsFilename("keg", "lol_2010-10-17_thin_S1_R0.xml.length");
  private static final GcsFilename RYDE_FILE =
      new GcsFilename("tub", "lol_2010-10-17_thin_S1_R0.ryde");
  private static final GcsFilename SIG_FILE =
      new GcsFilename("tub", "lol_2010-10-17_thin_S1_R0.sig");

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension
  public final GpgSystemCommandExtension gpg =
      new GpgSystemCommandExtension(
          RdeTestData.loadBytes("pgp-public-keyring.asc"),
          RdeTestData.loadBytes("pgp-private-keyring-escrow.asc"));

  private static PGPPublicKey encryptKey;
  private static PGPPrivateKey decryptKey;
  private static PGPPublicKey receiverKey;
  private static PGPKeyPair signingKey;

  @BeforeAll
  static void beforeAll() {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      encryptKey = keyring.getRdeStagingEncryptionKey();
      decryptKey = keyring.getRdeStagingDecryptionKey();
      receiverKey = keyring.getRdeReceiverKey();
      signingKey = keyring.getRdeSigningKey();
    }
  }

  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final GcsUtils gcsUtils = new GcsUtils(gcsService, 1024);
  private final BrdaCopyAction action = new BrdaCopyAction();

  @BeforeEach
  void beforeEach() throws Exception {
    action.gcsUtils = gcsUtils;
    action.tld = "lol";
    action.watermark = DateTime.parse("2010-10-17TZ");
    action.brdaBucket = "tub";
    action.stagingBucket = "keg";
    action.receiverKey = receiverKey;
    action.signingKey = signingKey;
    action.stagingDecryptionKey = decryptKey;

    byte[] xml = DEPOSIT_XML.read();
    GcsTestingUtils.writeGcsFile(gcsService, STAGE_FILE, Ghostryde.encode(xml, encryptKey));
    GcsTestingUtils.writeGcsFile(gcsService, STAGE_LENGTH_FILE,
        Long.toString(xml.length).getBytes(UTF_8));
  }

  @Test
  void testRun() {
    action.run();
    assertThat(gcsUtils.existsAndNotEmpty(STAGE_FILE)).isTrue();
    assertThat(gcsUtils.existsAndNotEmpty(RYDE_FILE)).isTrue();
    assertThat(gcsUtils.existsAndNotEmpty(SIG_FILE)).isTrue();
  }

  @Test
  void testRun_rydeFormat() throws Exception {
    assumeTrue(hasCommand("gpg --version"));
    action.run();

    File rydeTmp = new File(gpg.getCwd(), "ryde");
    Files.write(readGcsFile(gcsService, RYDE_FILE), rydeTmp);
    Process pid =
        gpg.exec(
            "gpg",
            "--list-packets",
            "--ignore-mdc-error",
            "--keyid-format",
            "long",
            rydeTmp.toString());
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
        .contains("name=\"lol_2010-10-17_thin_S1_R0.tar\"");
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

  @Test
  void testRun_rydeSignature() throws Exception {
    assumeTrue(hasCommand("gpg --version"));
    action.run();

    File rydeTmp = new File(gpg.getCwd(), "ryde");
    File sigTmp = new File(gpg.getCwd(), "ryde.sig");
    Files.write(readGcsFile(gcsService, RYDE_FILE), rydeTmp);
    Files.write(readGcsFile(gcsService, SIG_FILE), sigTmp);

    Process pid = gpg.exec("gpg", "--verify", sigTmp.toString(), rydeTmp.toString());
    String stderr = slurp(pid.getErrorStream());
    assertWithMessage(stderr).that(pid.waitFor()).isEqualTo(0);
    assertThat(stderr).contains("Good signature");
    assertThat(stderr).contains("rde-unittest@registry.test");
  }

  private String slurp(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, UTF_8));
  }
}
