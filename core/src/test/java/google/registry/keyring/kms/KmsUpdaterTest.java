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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.googlecode.objectify.Key;
import google.registry.keyring.api.KeySerializer;
import google.registry.model.server.KmsSecret;
import google.registry.model.server.KmsSecretRevision;
import google.registry.model.server.KmsSecretRevisionSqlDao;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.io.IOException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link KmsUpdater} */
@DualDatabaseTest
public class KmsUpdaterTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  private KmsUpdater updater;

  @BeforeEach
  void beforeEach() {
    updater = new KmsUpdater(new FakeKmsConnection());
  }

  @TestOfyAndSql
  void test_setMultipleSecrets() {
    updater
        .setMarksdbDnlLoginAndPassword("value1")
        .setIcannReportingPassword("value2")
        .setJsonCredential("value3")
        .update();

    verifySecretAndSecretRevisionWritten(
        "marksdb-dnl-login-string",
        "marksdb-dnl-login-string/foo",
        getCiphertext("value1"));
    verifySecretAndSecretRevisionWritten(
        "icann-reporting-password-string",
        "icann-reporting-password-string/foo",
        getCiphertext("value2"));
    verifySecretAndSecretRevisionWritten(
        "json-credential-string", "json-credential-string/foo", getCiphertext("value3"));
  }

  @TestOfyAndSql
  void test_setBrdaReceiverKey() throws Exception {
    updater.setBrdaReceiverPublicKey(KmsTestHelper.getPublicKey()).update();

    verifySecretAndSecretRevisionWritten(
        "brda-receiver-public",
        "brda-receiver-public/foo",
        getCiphertext(KmsTestHelper.getPublicKey()));
  }

  @TestOfyAndSql
  void test_setBrdaSigningKey() throws Exception {
    updater.setBrdaSigningKey(KmsTestHelper.getKeyPair()).update();

    verifySecretAndSecretRevisionWritten(
        "brda-signing-private",
        "brda-signing-private/foo",
        getCiphertext(KmsTestHelper.getKeyPair()));
    verifySecretAndSecretRevisionWritten(
        "brda-signing-public",
        "brda-signing-public/foo",
        getCiphertext(KmsTestHelper.getPublicKey()));
  }

  @TestOfyAndSql
  void test_setIcannReportingPassword() {
    updater.setIcannReportingPassword("value1").update();

    verifySecretAndSecretRevisionWritten(
        "icann-reporting-password-string",
        "icann-reporting-password-string/foo",
        getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setJsonCredential() {
    updater.setJsonCredential("value1").update();

    verifySecretAndSecretRevisionWritten(
        "json-credential-string", "json-credential-string/foo", getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setMarksdbDnlLoginAndPassword() {
    updater.setMarksdbDnlLoginAndPassword("value1").update();

    verifySecretAndSecretRevisionWritten(
        "marksdb-dnl-login-string", "marksdb-dnl-login-string/foo", getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setMarksdbLordnPassword() {
    updater.setMarksdbLordnPassword("value1").update();

    verifySecretAndSecretRevisionWritten(
        "marksdb-lordn-password-string",
        "marksdb-lordn-password-string/foo",
        getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setMarksdbSmdrlLoginAndPassword() {
    updater.setMarksdbSmdrlLoginAndPassword("value1").update();

    verifySecretAndSecretRevisionWritten(
        "marksdb-smdrl-login-string", "marksdb-smdrl-login-string/foo", getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setRdeReceiverKey() throws Exception {
    updater.setRdeReceiverPublicKey(KmsTestHelper.getPublicKey()).update();

    verifySecretAndSecretRevisionWritten(
        "rde-receiver-public",
        "rde-receiver-public/foo",
        getCiphertext(
            KeySerializer.serializePublicKey(KmsTestHelper.getPublicKey())));
  }

  @TestOfyAndSql
  void test_setRdeSigningKey() throws Exception {
    updater.setRdeSigningKey(KmsTestHelper.getKeyPair()).update();

    verifySecretAndSecretRevisionWritten(
        "rde-signing-private",
        "rde-signing-private/foo",
        getCiphertext(KmsTestHelper.getKeyPair()));
    verifySecretAndSecretRevisionWritten(
        "rde-signing-public",
        "rde-signing-public/foo",
        getCiphertext(KmsTestHelper.getPublicKey()));
  }

  @TestOfyAndSql
  void test_setRdeSshClientPrivateKey() {
    updater.setRdeSshClientPrivateKey("value1").update();

    verifySecretAndSecretRevisionWritten(
        "rde-ssh-client-private-string",
        "rde-ssh-client-private-string/foo",
        getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setRdeSshClientPublicKey() {
    updater.setRdeSshClientPublicKey("value1").update();

    verifySecretAndSecretRevisionWritten(
        "rde-ssh-client-public-string",
        "rde-ssh-client-public-string/foo",
        getCiphertext("value1"));
  }

  @TestOfyAndSql
  void test_setRdeStagingKey() throws Exception {
    updater.setRdeStagingKey(KmsTestHelper.getKeyPair()).update();

    verifySecretAndSecretRevisionWritten(
        "rde-staging-private",
        "rde-staging-private/foo",
        getCiphertext(KmsTestHelper.getKeyPair()));
    verifySecretAndSecretRevisionWritten(
        "rde-staging-public",
        "rde-staging-public/foo",
        getCiphertext(KmsTestHelper.getPublicKey()));
  }

  private static void verifySecretAndSecretRevisionWritten(
      String secretName, String expectedCryptoKeyVersionName, String expectedEncryptedValue) {
    KmsSecretRevision secretRevision;
    if (tm().isOfy()) {
      KmsSecret secret =
          ofy().load().key(Key.create(getCrossTldKey(), KmsSecret.class, secretName)).now();
      assertThat(secret).isNotNull();
      secretRevision = ofy().load().key(secret.getLatestRevision()).now();
    } else {
      secretRevision =
          tm().transact(() -> KmsSecretRevisionSqlDao.getLatestRevision(secretName).get());
    }
    assertThat(secretRevision.getKmsCryptoKeyVersionName()).isEqualTo(expectedCryptoKeyVersionName);
    assertThat(secretRevision.getEncryptedValue()).isEqualTo(expectedEncryptedValue);
  }

  private static String getCiphertext(byte[] plaintext) {
    return new FakeKmsConnection().encrypt("blah", plaintext).ciphertext();
  }

  private static String getCiphertext(String plaintext) {
    return getCiphertext(KeySerializer.serializeString(plaintext));
  }

  private static String getCiphertext(PGPPublicKey publicKey) throws IOException {
    return getCiphertext(KeySerializer.serializePublicKey(publicKey));
  }

  private static String getCiphertext(PGPKeyPair keyPair) throws Exception {
    return getCiphertext(KeySerializer.serializeKeyPair(keyPair));
  }
}
