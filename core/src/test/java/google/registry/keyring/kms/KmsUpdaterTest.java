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

package google.registry.keyring.kms;

import static com.google.common.truth.Truth.assertThat;

import google.registry.keyring.api.KeySerializer;
import google.registry.privileges.secretmanager.FakeSecretManagerClient;
import google.registry.privileges.secretmanager.KeyringSecretStore;
import google.registry.testing.BouncyCastleProviderExtension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link KmsKeyring} and {@link KmsUpdater} */
// TODO(2021-07-01): Rename this class along with KmsKeyring
public class KmsUpdaterTest {

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  private KeyringSecretStore secretStore;
  private KmsUpdater updater;
  private KmsKeyring keyring;

  @BeforeEach
  void beforeEach() {
    secretStore = new KeyringSecretStore(new FakeSecretManagerClient());
    updater = new KmsUpdater(secretStore);
    keyring = new KmsKeyring(secretStore);
  }

  @Test
  void setAndReadMultiple() {
    String secretPrefix = "setAndReadMultiple_";
    updater
        .setMarksdbDnlLoginAndPassword(secretPrefix + "marksdb")
        .setIcannReportingPassword(secretPrefix + "icann")
        .setJsonCredential(secretPrefix + "json")
        .update();

    assertThat(keyring.getMarksdbDnlLoginAndPassword()).isEqualTo(secretPrefix + "marksdb");
    assertThat(keyring.getIcannReportingPassword()).isEqualTo(secretPrefix + "icann");
    assertThat(keyring.getJsonCredential()).isEqualTo(secretPrefix + "json");

    verifyPersistedSecret("marksdb-dnl-login-string", secretPrefix + "marksdb");
    verifyPersistedSecret("icann-reporting-password-string", secretPrefix + "icann");
    verifyPersistedSecret("json-credential-string", secretPrefix + "json");
  }

  @Test
  void brdaReceiverKey() throws Exception {
    PGPPublicKey publicKey = KmsTestHelper.getPublicKey();
    updater.setBrdaReceiverPublicKey(publicKey).update();

    assertThat(keyring.getBrdaReceiverKey().getFingerprint()).isEqualTo(publicKey.getFingerprint());
    verifyPersistedSecret("brda-receiver-public", serializePublicKey(publicKey));
  }

  @Test
  void brdaSigningKey() throws Exception {
    PGPKeyPair keyPair = KmsTestHelper.getKeyPair();
    updater.setBrdaSigningKey(keyPair).update();

    assertThat(serializeKeyPair(keyring.getBrdaSigningKey())).isEqualTo(serializeKeyPair(keyPair));
    verifyPersistedSecret("brda-signing-private", serializeKeyPair(KmsTestHelper.getKeyPair()));
    verifyPersistedSecret("brda-signing-public", serializePublicKey(KmsTestHelper.getPublicKey()));
  }

  @Test
  void icannReportingPassword() {
    String secret = "icannReportingPassword";
    updater.setIcannReportingPassword(secret).update();

    assertThat(keyring.getIcannReportingPassword()).isEqualTo(secret);
    verifyPersistedSecret("icann-reporting-password-string", secret);
  }

  @Test
  void jsonCredential() {
    String secret = "jsonCredential";
    updater.setJsonCredential(secret).update();

    assertThat(keyring.getJsonCredential()).isEqualTo(secret);
    verifyPersistedSecret("json-credential-string", secret);
  }

  @Test
  void marksdbDnlLoginAndPassword() {
    String secret = "marksdbDnlLoginAndPassword";
    updater.setMarksdbDnlLoginAndPassword(secret).update();

    assertThat(keyring.getMarksdbDnlLoginAndPassword()).isEqualTo(secret);
    verifyPersistedSecret("marksdb-dnl-login-string", secret);
  }

  @Test
  void marksdbLordnPassword() {
    String secret = "marksdbLordnPassword";
    updater.setMarksdbLordnPassword(secret).update();

    assertThat(keyring.getMarksdbLordnPassword()).isEqualTo(secret);
    verifyPersistedSecret("marksdb-lordn-password-string", secret);
  }

  @Test
  void marksdbSmdrlLoginAndPassword() {
    String secret = "marksdbSmdrlLoginAndPassword";
    updater.setMarksdbSmdrlLoginAndPassword(secret).update();

    assertThat(keyring.getMarksdbSmdrlLoginAndPassword()).isEqualTo(secret);
    verifyPersistedSecret("marksdb-smdrl-login-string", secret);
  }

  @Test
  void rdeReceiverKey() throws Exception {
    PGPPublicKey publicKey = KmsTestHelper.getPublicKey();
    updater.setRdeReceiverPublicKey(publicKey).update();

    assertThat(keyring.getRdeReceiverKey().getFingerprint()).isEqualTo(publicKey.getFingerprint());
    verifyPersistedSecret("rde-receiver-public", serializePublicKey(KmsTestHelper.getPublicKey()));
  }

  @Test
  void rdeSigningKey() throws Exception {
    PGPKeyPair keyPair = KmsTestHelper.getKeyPair();
    updater.setRdeSigningKey(keyPair).update();

    assertThat(serializeKeyPair(keyring.getRdeSigningKey())).isEqualTo(serializeKeyPair(keyPair));

    verifyPersistedSecret("rde-signing-private", serializeKeyPair(keyPair));
    verifyPersistedSecret("rde-signing-public", serializePublicKey(keyPair.getPublicKey()));
  }

  @Test
  void rdeSshClientPrivateKey() {
    String secret = "rdeSshClientPrivateKey";
    updater.setRdeSshClientPrivateKey(secret).update();

    assertThat(keyring.getRdeSshClientPrivateKey()).isEqualTo(secret);
    verifyPersistedSecret("rde-ssh-client-private-string", secret);
  }

  @Test
  void rdeSshClientPublicKey() {
    String secret = "rdeSshClientPublicKey";
    updater.setRdeSshClientPublicKey(secret).update();

    assertThat(keyring.getRdeSshClientPublicKey()).isEqualTo(secret);
    verifyPersistedSecret("rde-ssh-client-public-string", secret);
  }

  @Test
  void rdeStagingKey() throws Exception {
    PGPKeyPair keyPair = KmsTestHelper.getKeyPair();
    updater.setRdeStagingKey(keyPair).update();

    assertThat(serializePublicKey(keyring.getRdeStagingEncryptionKey()))
        .isEqualTo(serializePublicKey(keyPair.getPublicKey()));
    // Since we do not have dedicated tools to compare private keys, we leverage key-pair
    // serialization util to compare private keys.
    assertThat(
            serializeKeyPair(
                new PGPKeyPair(
                    keyring.getRdeStagingEncryptionKey(), keyring.getRdeStagingDecryptionKey())))
        .isEqualTo(serializeKeyPair(keyPair));
    verifyPersistedSecret("rde-staging-private", serializeKeyPair(keyPair));
    verifyPersistedSecret("rde-staging-public", serializePublicKey(KmsTestHelper.getPublicKey()));
  }

  private void verifyPersistedSecret(String secretName, String expectedPlainTextValue) {
    assertThat(new String(secretStore.getSecret(secretName), StandardCharsets.UTF_8))
        .isEqualTo(expectedPlainTextValue);
  }

  private static String serializePublicKey(PGPPublicKey publicKey) throws IOException {
    return new String(KeySerializer.serializePublicKey(publicKey), StandardCharsets.UTF_8);
  }

  private static String serializeKeyPair(PGPKeyPair keyPair) throws Exception {
    return new String(KeySerializer.serializeKeyPair(keyPair), StandardCharsets.UTF_8);
  }
}
