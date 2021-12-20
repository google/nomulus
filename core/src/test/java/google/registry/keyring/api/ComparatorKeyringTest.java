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

package google.registry.keyring.api;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.testing.TestLogHandler;
import google.registry.util.JdkLoggerConfig;
import java.io.IOException;
import java.util.logging.Level;
import org.bouncycastle.bcpg.BCPGKey;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ComparatorKeyring}. */
class ComparatorKeyringTest {

  private static final String PUBLIC_KEY_FINGERPRINT = "fingerprint";
  private static final String PUBLIC_KEY_TO_STRING =
      "PGPPublicKey{fingerprint=66:69:6e:67:65:72:70:72:69:6e:74:}";
  private static final String PRIVATE_KEY_TO_STRING =
      "PGPPrivateKey{keyId=1}";
  private static final String KEY_PAIR_TO_STRING =
      String.format("PGPKeyPair{%s, %s}", PUBLIC_KEY_TO_STRING, PRIVATE_KEY_TO_STRING);

  private static PGPPublicKey mockPublicKey(
      boolean altFingerprint,
      boolean altEncoded) throws IOException {
    PGPPublicKey publicKey = mock(PGPPublicKey.class);
    String fingerprint = altFingerprint ? "alternate" : PUBLIC_KEY_FINGERPRINT;
    String encoded = altEncoded ? "alternate" : "publicKeyEncoded";
    when(publicKey.getFingerprint()).thenReturn(fingerprint.getBytes(UTF_8));
    when(publicKey.getEncoded()).thenReturn(encoded.getBytes(UTF_8));
    return publicKey;
  }

  private static PGPPrivateKey mockPrivateKey(
      boolean altId,
      boolean altBcpgKeyFormat,
      boolean altBcpgKeyEncoded,
      boolean altPublicKeyPacketEncoded)
      throws IOException {
    String bcpgKeyFormat = altBcpgKeyFormat ? "alternate" : "bcpgFormat";
    String bcpgKeyEncoded = altBcpgKeyEncoded ? "alternate" : "bcpgEncoded";
    String publicKeyPacketEncoded = altPublicKeyPacketEncoded ? "alternate" : "packetEncoded";

    BCPGKey bcpgKey = mock(BCPGKey.class);
    PublicKeyPacket publicKeyPacket = mock(PublicKeyPacket.class);
    when(bcpgKey.getFormat()).thenReturn(bcpgKeyFormat);
    when(bcpgKey.getEncoded()).thenReturn(bcpgKeyEncoded.getBytes(UTF_8));
    when(publicKeyPacket.getEncoded()).thenReturn(publicKeyPacketEncoded.getBytes(UTF_8));
    return new PGPPrivateKey(altId ? 2 : 1, publicKeyPacket, bcpgKey);
  }

  private final TestLogHandler testLogHandler = new TestLogHandler();

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(ComparatorKeyring.class).addHandler(testLogHandler);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(ComparatorKeyring.class).removeHandler(testLogHandler);
  }

  @Test
  void testPublicKeyToString() throws Exception {
    assertThat(
            ComparatorKeyring.stringify(
                mockPublicKey(false, false)))
        .isEqualTo(PUBLIC_KEY_TO_STRING);
  }

  @Test
  void testPublicKeyEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPublicKey(false, false),
                mockPublicKey(false, false)))
        .isTrue();
  }

  @Test
  void testPublicKeyDifferFingerprint_notEqual() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPublicKey(false, false),
                mockPublicKey(true, false)))
        .isFalse();
  }

  @Test
  void testPublicKeyDifferEncoded_notEqual() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPublicKey(false, false),
                mockPublicKey(false, true)))
        .isFalse();
  }

  @Test
  void testPrivateKeyToString() throws Exception {
    assertThat(
            ComparatorKeyring.stringify(
                mockPrivateKey(false, false, false, false)))
        .isEqualTo(PRIVATE_KEY_TO_STRING);
  }

  @Test
  void testPrivateKeyEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPrivateKey(false, false, false, false),
                mockPrivateKey(false, false, false, false)))
        .isTrue();
  }

  @Test
  void testPrivateKeyDifferId_notEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPrivateKey(false, false, false, false),
                mockPrivateKey(true, false, false, false)))
        .isFalse();
  }

  @Test
  void testPrivateKeyDifferBcpgFormat_notEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPrivateKey(false, false, false, false),
                mockPrivateKey(false, true, false, false)))
        .isFalse();
  }

  @Test
  void testPrivateKeyDifferBcpgEncoding_notEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPrivateKey(false, false, false, false),
                mockPrivateKey(false, false, true, false)))
        .isFalse();
  }

  @Test
  void testPrivateKeyDifferPublicKeyEncoding_notEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                mockPrivateKey(false, false, false, false),
                mockPrivateKey(false, false, false, true)))
        .isFalse();
  }

  @Test
  void testKeyPairToString() throws Exception {
    assertThat(
            ComparatorKeyring.stringify(
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(false, false, false, false))))
        .isEqualTo(KEY_PAIR_TO_STRING);
  }

  @Test
  void testKeyPairEquals() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(false, false, false, false)),
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(false, false, false, false))))
        .isTrue();
  }

  @Test
  void testKeyPairDifferPublicKey_notEqual() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(false, false, false, false)),
                new PGPKeyPair(
                    mockPublicKey(true, false),
                    mockPrivateKey(false, false, false, false))))
        .isFalse();
  }

  @Test
  void testKeyPairDifferPrivateKey_notEqual() throws Exception {
    assertThat(
            ComparatorKeyring.compare(
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(false, false, false, false)),
                new PGPKeyPair(
                    mockPublicKey(false, false),
                    mockPrivateKey(true, false, false, false))))
        .isFalse();
  }

  // We don't need to check every single method in the generated instance to see that it behaves
  // correctly. This should have been tested in ComparatorGenerator.
  //
  // We will fully test a single method just to make sure everything is "connected" correctly.

  @Test
  void testRdeSigningKey_actualThrows() throws Exception {
    Keyring actualKeyring = mock(Keyring.class);
    Keyring secondKeyring = mock(Keyring.class);
    PGPKeyPair keyPair =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(false, false, false, false));
    when(actualKeyring.getRdeSigningKey()).thenThrow(new KeyringException("message"));
    when(secondKeyring.getRdeSigningKey()).thenReturn(keyPair);
    Keyring comparatorKeyring = ComparatorKeyring.create(actualKeyring, secondKeyring);

    assertThrows(KeyringException.class, comparatorKeyring::getRdeSigningKey);

    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE, ".getRdeSigningKey: Only actual implementation threw exception");
  }

  @Test
  void testRdeSigningKey_secondThrows() throws Exception {
    Keyring actualKeyring = mock(Keyring.class);
    Keyring secondKeyring = mock(Keyring.class);
    PGPKeyPair keyPair =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(false, false, false, false));
    when(actualKeyring.getRdeSigningKey()).thenReturn(keyPair);
    when(secondKeyring.getRdeSigningKey()).thenThrow(new KeyringException("message"));
    Keyring comparatorKeyring = ComparatorKeyring.create(actualKeyring, secondKeyring);

    assertThat(comparatorKeyring.getRdeSigningKey()).isSameInstanceAs(keyPair);

    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE, ".getRdeSigningKey: Only second implementation threw exception");
  }

  @Test
  void testRdeSigningKey_bothThrow() {
    Keyring actualKeyring = mock(Keyring.class);
    Keyring secondKeyring = mock(Keyring.class);
    when(actualKeyring.getRdeSigningKey()).thenThrow(new KeyringException("message"));
    when(secondKeyring.getRdeSigningKey()).thenThrow(new KeyringException("message"));
    Keyring comparatorKeyring = ComparatorKeyring.create(actualKeyring, secondKeyring);

    assertThrows(KeyringException.class, comparatorKeyring::getRdeSigningKey);

    assertAboutLogs().that(testLogHandler).hasNoLogsAtLevel(Level.SEVERE);
  }

  @Test
  void testRdeSigningKey_same() throws Exception {
    Keyring actualKeyring = mock(Keyring.class);
    Keyring secondKeyring = mock(Keyring.class);
    PGPKeyPair keyPair =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(false, false, false, false));
    PGPKeyPair keyPairCopy =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(false, false, false, false));
    when(actualKeyring.getRdeSigningKey()).thenReturn(keyPair);
    when(secondKeyring.getRdeSigningKey()).thenReturn(keyPairCopy);
    Keyring comparatorKeyring = ComparatorKeyring.create(actualKeyring, secondKeyring);

    assertThat(comparatorKeyring.getRdeSigningKey()).isSameInstanceAs(keyPair);

    assertAboutLogs().that(testLogHandler).hasNoLogsAtLevel(Level.SEVERE);
  }

  @Test
  void testRdeSigningKey_different() throws Exception {
    Keyring actualKeyring = mock(Keyring.class);
    Keyring secondKeyring = mock(Keyring.class);
    PGPKeyPair keyPair =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(false, false, false, false));
    PGPKeyPair keyPairDifferent =
        new PGPKeyPair(
            mockPublicKey(false, false),
            mockPrivateKey(true, false, false, false));
    when(actualKeyring.getRdeSigningKey()).thenReturn(keyPair);
    when(secondKeyring.getRdeSigningKey()).thenReturn(keyPairDifferent);
    Keyring comparatorKeyring = ComparatorKeyring.create(actualKeyring, secondKeyring);

    assertThat(comparatorKeyring.getRdeSigningKey()).isSameInstanceAs(keyPair);

    String alternateKeyPairString = String.format(
        "PGPKeyPair{%s, %s}", PUBLIC_KEY_TO_STRING, "PGPPrivateKey{keyId=2}");

    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE,
            String.format(
                ".getRdeSigningKey: Got different results! '%s' vs '%s'",
                KEY_PAIR_TO_STRING,
                alternateKeyPairString));
  }
}
