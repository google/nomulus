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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.keyring.api.PgpHelper.KeyRequirement.ENCRYPT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeKeyringModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RydeEncryptionTest {

  @Rule public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @Test
  public void testSuccess_oneReceiver_decryptWithCorrectKey() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair key = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream encryptor =
        RydeEncryption.openEncryptor(output, false, ImmutableList.of(key.getPublicKey()))) {
      encryptor.write(expected);
    }
    byte[] encryptedData = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(encryptedData);
    try (InputStream decryptor = RydeEncryption.openDecryptor(input, false, key.getPrivateKey())) {
      assertThat(ByteStreams.toByteArray(decryptor)).isEqualTo(expected);
    }
  }

  @Test
  public void testFail_oneReceiver_decryptWithWrongKey() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair key = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    PGPKeyPair wrongKey = keyringModule.get("rde-unittest-dsa@registry.test", ENCRYPT);
    assertThat(key.getKeyID()).isNotEqualTo(wrongKey.getKeyID());
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream encryptor =
        RydeEncryption.openEncryptor(output, false, ImmutableList.of(key.getPublicKey()))) {
      encryptor.write(expected);
    }
    byte[] encryptedData = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(encryptedData);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> {
              RydeEncryption.openDecryptor(input, false, wrongKey.getPrivateKey()).read();
            });

    assertThat(thrown).hasCauseThat().isInstanceOf(PGPException.class);
  }

  @Test
  public void testSuccess_twoReceivers() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair key1 = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    PGPKeyPair key2 = keyringModule.get("rde-unittest-dsa@registry.test", ENCRYPT);
    assertThat(key1.getKeyID()).isNotEqualTo(key2.getKeyID());
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream encryptor =
        RydeEncryption.openEncryptor(
            output, false, ImmutableList.of(key1.getPublicKey(), key2.getPublicKey()))) {
      encryptor.write(expected);
    }
    byte[] encryptedData = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(encryptedData);
    try (InputStream decryptor = RydeEncryption.openDecryptor(input, false, key1.getPrivateKey())) {
      assertThat(ByteStreams.toByteArray(decryptor)).isEqualTo(expected);
    }

    input.reset();
    try (InputStream decryptor = RydeEncryption.openDecryptor(input, false, key2.getPrivateKey())) {
      assertThat(ByteStreams.toByteArray(decryptor)).isEqualTo(expected);
    }
  }

  @Test
  public void testSuccess_decryptHasntChanged() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair key = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);
    byte[] encryptedData =
        Base64.getMimeDecoder()
            .decode(
                "hQEMA6WcEy81iaHVAQf+I14Ewo1Fr6epwqtUoMSuy3qtobayZI54u/ohyMBgnpfts8B15320x4eO"
                    + "ElbaMKLJFZzOI8IsJRlX9mpSMp+qALdhOjXfM4q9wHNPKTRXqkhhblyTt7r4MTRp1w8lTA8R5hGO"
                    + "MCoxYwicK7DYrqL728FCeA2UBaQVXB6FZIIjujwNRzghvyqGDLLF6LxnR8ovB2PqT4Ho0wTmHWNy"
                    + "CZWyR5y9TBgTZWpIoNFuHQGe8egz/rTR+ixp1Ru3lxib7xuJVQyjbiGMO+lk4ffeEg4KpwEFblMx"
                    + "s17nxCrT5E30qktKjRQopvGICSrxyMGrbyUu5HdASZDj4jyqgP152KxJ18khC05Kf6zT4ouLoJHB"
                    + "XENDmLN3Onf6IwR043Lk0KISKi6z");

    ByteArrayInputStream input = new ByteArrayInputStream(encryptedData);
    try (InputStream decryptor = RydeEncryption.openDecryptor(input, false, key.getPrivateKey())) {
      assertThat(ByteStreams.toByteArray(decryptor)).isEqualTo(expected);
    }
  }

  @Test
  public void testSuccess_oneReceiver_withIntegrityPacket() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair key = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream encryptor =
        RydeEncryption.openEncryptor(output, true, ImmutableList.of(key.getPublicKey()))) {
      encryptor.write(expected);
    }
    byte[] encryptedData = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(encryptedData);
    try (InputStream decryptor = RydeEncryption.openDecryptor(input, true, key.getPrivateKey())) {
      assertThat(ByteStreams.toByteArray(decryptor)).isEqualTo(expected);
    }
  }
}
