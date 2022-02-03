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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TestDataHelper.loadBytes;

import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import google.registry.rde.RdeTestData;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeKeyringModule;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EncryptEscrowDepositCommand}. */
public class EncryptEscrowDepositCommandTest
    extends CommandTestCase<EncryptEscrowDepositCommand> {

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  private final ByteSource depositXml = loadBytes(RdeTestData.class, "deposit_full.xml");

  static EscrowDepositEncryptor createEncryptor() {
    EscrowDepositEncryptor res = new EscrowDepositEncryptor();
    res.rdeReceiverKey = () -> new FakeKeyringModule().get().getRdeReceiverKey();
    res.rdeSigningKey = () -> new FakeKeyringModule().get().getRdeSigningKey();
    res.brdaReceiverKey = () -> new FakeKeyringModule().get().getBrdaReceiverKey();
    res.brdaSigningKey = () -> new FakeKeyringModule().get().getBrdaSigningKey();
    return res;
  }

  @BeforeEach
  void beforeEach() {
    command.encryptor = createEncryptor();
  }

  @Test
  void testSuccess() throws Exception {
    Path depositFile = tmpDir.resolve("deposit.xml");
    Files.write(depositXml.read(), depositFile.toFile());
    runCommand("--tld=lol", "--input=" + depositFile, "--outdir=" + tmpDir.toString());
    assertThat(tmpDir.toFile().list())
        .asList()
        .containsExactly(
            "deposit.xml",
            "lol_2010-10-17_full_S1_R0.ryde",
            "lol_2010-10-17_full_S1_R0.sig",
            "lol.pub");
  }

  @Test
  void testSuccess_brda() throws Exception {
    Path depositFile = tmpDir.resolve("deposit.xml");
    Files.write(depositXml.read(), depositFile.toFile());
    runCommand(
        "--mode=THIN", "--tld=lol", "--input=" + depositFile, "--outdir=" + tmpDir.toString());
    assertThat(tmpDir.toFile().list())
        .asList()
        .containsExactly(
            "deposit.xml",
            "lol_2010-10-17_thin_S1_R0.ryde",
            "lol_2010-10-17_thin_S1_R0.sig",
            "lol.pub");
  }

  @Test
  void testSuccess_revision() throws Exception {
    Path depositFile = tmpDir.resolve("deposit.xml");
    Files.write(depositXml.read(), depositFile.toFile());
    runCommand(
        "--revision=1", "--tld=lol", "--input=" + depositFile, "--outdir=" + tmpDir.toString());
    assertThat(tmpDir.toFile().list())
        .asList()
        .containsExactly(
            "deposit.xml",
            "lol_2010-10-17_full_S1_R1.ryde",
            "lol_2010-10-17_full_S1_R1.sig",
            "lol.pub");
  }
}
