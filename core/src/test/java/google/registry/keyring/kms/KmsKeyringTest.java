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
import static google.registry.testing.DatastoreHelper.persistResources;

import com.google.common.collect.ImmutableList;
import google.registry.keyring.api.KeySerializer;
import google.registry.model.server.KmsSecret;
import google.registry.model.server.KmsSecretRevision;
import google.registry.testing.AppEngineRule;
import google.registry.testing.BouncyCastleProviderRule;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class KmsKeyringTest {

  @Rule public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private KmsKeyring keyring;

  @Before
  public void setUp() {
    keyring = new KmsKeyring(new FakeKmsConnection());
  }

  @Test
  public void test_getCloudSqlPassword() throws Exception {
    saveCleartextSecret("cloud-sql-password-string");

    String cloudSqlPassword = keyring.getCloudSqlPassword();

    assertThat(cloudSqlPassword).isEqualTo("cloud-sql-password-stringmoo");
  }

  @Test
  public void test_getToolsCloudSqlPassword() throws Exception {
    saveCleartextSecret("tools-cloud-sql-password-string");

    String toolsCloudSqlPassword = keyring.getToolsCloudSqlPassword();

    assertThat(toolsCloudSqlPassword).isEqualTo("tools-cloud-sql-password-stringmoo");
  }

  @Test
  public void test_getRdeSigningKey() throws Exception {
    saveKeyPairSecret("rde-signing-public", "rde-signing-private");

    PGPKeyPair rdeSigningKey = keyring.getRdeSigningKey();

    assertThat(KeySerializer.serializeKeyPair(rdeSigningKey))
        .isEqualTo(KeySerializer.serializeKeyPair(KmsTestHelper.getKeyPair()));
  }

  @Test
  public void test_getRdeStagingEncryptionKey() throws Exception {
    savePublicKeySecret("rde-staging-public");

    PGPPublicKey rdeStagingEncryptionKey = keyring.getRdeStagingEncryptionKey();

    assertThat(rdeStagingEncryptionKey.getFingerprint())
        .isEqualTo(KmsTestHelper.getPublicKey().getFingerprint());
  }

  @Test
  public void test_getRdeStagingDecryptionKey() throws Exception {
    savePrivateKeySecret("rde-staging-private");
    savePublicKeySecret("rde-staging-public");

    PGPPrivateKey rdeStagingDecryptionKey = keyring.getRdeStagingDecryptionKey();
    PGPPublicKey rdeStagingEncryptionKey = keyring.getRdeStagingEncryptionKey();
    PGPKeyPair keyPair = new PGPKeyPair(rdeStagingEncryptionKey, rdeStagingDecryptionKey);

    assertThat(KeySerializer.serializeKeyPair(keyPair))
        .isEqualTo(KeySerializer.serializeKeyPair(KmsTestHelper.getKeyPair()));
  }

  @Test
  public void test_getRdeReceiverKey() throws Exception {
    savePublicKeySecret("rde-receiver-public");

    PGPPublicKey rdeReceiverKey = keyring.getRdeReceiverKey();

    assertThat(rdeReceiverKey.getFingerprint())
        .isEqualTo(KmsTestHelper.getPublicKey().getFingerprint());
  }

  @Test
  public void test_getBrdaSigningKey() throws Exception {
    saveKeyPairSecret("brda-signing-public", "brda-signing-private");

    PGPKeyPair brdaSigningKey = keyring.getBrdaSigningKey();

    assertThat(KeySerializer.serializeKeyPair(brdaSigningKey))
        .isEqualTo(KeySerializer.serializeKeyPair(KmsTestHelper.getKeyPair()));
  }

  @Test
  public void test_getBrdaReceiverKey() throws Exception {
    savePublicKeySecret("brda-receiver-public");

    PGPPublicKey brdaReceiverKey = keyring.getBrdaReceiverKey();

    assertThat(brdaReceiverKey.getFingerprint())
        .isEqualTo(KmsTestHelper.getPublicKey().getFingerprint());
  }

  @Test
  public void test_getRdeSshClientPublicKey() {
    saveCleartextSecret("rde-ssh-client-public-string");

    String rdeSshClientPublicKey = keyring.getRdeSshClientPublicKey();

    assertThat(rdeSshClientPublicKey).isEqualTo("rde-ssh-client-public-stringmoo");
  }

  @Test
  public void test_getRdeSshClientPrivateKey() {
    saveCleartextSecret("rde-ssh-client-private-string");

    String rdeSshClientPrivateKey = keyring.getRdeSshClientPrivateKey();

    assertThat(rdeSshClientPrivateKey).isEqualTo("rde-ssh-client-private-stringmoo");
  }

  @Test
  public void test_getIcannReportingPassword() {
    saveCleartextSecret("icann-reporting-password-string");

    String icannReportingPassword = keyring.getIcannReportingPassword();

    assertThat(icannReportingPassword).isEqualTo("icann-reporting-password-stringmoo");
  }

  @Test
  public void test_getMarksdbDnlLoginAndPassword() {
    saveCleartextSecret("marksdb-dnl-login-string");

    String marksdbDnlLoginAndPassword = keyring.getMarksdbDnlLoginAndPassword();

    assertThat(marksdbDnlLoginAndPassword).isEqualTo("marksdb-dnl-login-stringmoo");
  }

  @Test
  public void test_getMarksdbLordnPassword() {
    saveCleartextSecret("marksdb-lordn-password-string");

    String marksdbLordnPassword = keyring.getMarksdbLordnPassword();

    assertThat(marksdbLordnPassword).isEqualTo("marksdb-lordn-password-stringmoo");
  }

  @Test
  public void test_getMarksdbSmdrlLoginAndPassword() {
    saveCleartextSecret("marksdb-smdrl-login-string");

    String marksdbSmdrlLoginAndPassword = keyring.getMarksdbSmdrlLoginAndPassword();

    assertThat(marksdbSmdrlLoginAndPassword).isEqualTo("marksdb-smdrl-login-stringmoo");

  }

  @Test
  public void test_getJsonCredential() {
    saveCleartextSecret("json-credential-string");

    String jsonCredential = keyring.getJsonCredential();

    assertThat(jsonCredential).isEqualTo("json-credential-stringmoo");
  }

  private static void persistSecret(String secretName, byte[] secretValue) {
    KmsConnection kmsConnection = new FakeKmsConnection();

    KmsSecretRevision secretRevision =
        new KmsSecretRevision.Builder()
            .setEncryptedValue(kmsConnection.encrypt(secretName, secretValue).ciphertext())
            .setKmsCryptoKeyVersionName(KmsTestHelper.DUMMY_CRYPTO_KEY_VERSION)
            .setParent(secretName)
            .build();
    KmsSecret secret = KmsSecret.create(secretName, secretRevision);
    persistResources(ImmutableList.of(secretRevision, secret));
  }

  private static void saveCleartextSecret(String secretName) {
    persistSecret(secretName, KeySerializer.serializeString(secretName + "moo"));
  }

  private static void savePublicKeySecret(String publicKeyName) throws Exception {
    persistSecret(publicKeyName, KeySerializer.serializePublicKey(KmsTestHelper.getPublicKey()));
  }

  private static void savePrivateKeySecret(String privateKeyName) throws Exception {
    persistSecret(privateKeyName, KeySerializer.serializeKeyPair(KmsTestHelper.getKeyPair()));
  }

  private static void saveKeyPairSecret(String publicKeyName, String privateKeyName)
      throws Exception {
    savePublicKeySecret(publicKeyName);
    savePrivateKeySecret(privateKeyName);
  }
}
