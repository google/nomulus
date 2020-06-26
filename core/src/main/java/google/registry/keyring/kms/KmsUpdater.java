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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.keyring.kms.KmsKeyring.PrivateKeyLabel.BRDA_SIGNING_PRIVATE;
import static google.registry.keyring.kms.KmsKeyring.PrivateKeyLabel.RDE_SIGNING_PRIVATE;
import static google.registry.keyring.kms.KmsKeyring.PrivateKeyLabel.RDE_STAGING_PRIVATE;
import static google.registry.keyring.kms.KmsKeyring.PublicKeyLabel.BRDA_RECEIVER_PUBLIC;
import static google.registry.keyring.kms.KmsKeyring.PublicKeyLabel.BRDA_SIGNING_PUBLIC;
import static google.registry.keyring.kms.KmsKeyring.PublicKeyLabel.RDE_RECEIVER_PUBLIC;
import static google.registry.keyring.kms.KmsKeyring.PublicKeyLabel.RDE_SIGNING_PUBLIC;
import static google.registry.keyring.kms.KmsKeyring.PublicKeyLabel.RDE_STAGING_PUBLIC;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.CLOUD_SQL_PASSWORD_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.ICANN_REPORTING_PASSWORD_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.JSON_CREDENTIAL_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.MARKSDB_DNL_LOGIN_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.MARKSDB_LORDN_PASSWORD_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.MARKSDB_SMDRL_LOGIN_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.RDE_SSH_CLIENT_PRIVATE_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.RDE_SSH_CLIENT_PUBLIC_STRING;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.SAFE_BROWSING_API_KEY;
import static google.registry.keyring.kms.KmsKeyring.StringKeyLabel.TOOLS_CLOUD_SQL_PASSWORD_STRING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import google.registry.keyring.api.KeySerializer;
import google.registry.keyring.kms.KmsKeyring.PrivateKeyLabel;
import google.registry.keyring.kms.KmsKeyring.PublicKeyLabel;
import google.registry.keyring.kms.KmsKeyring.StringKeyLabel;
import google.registry.model.server.KmsSecret;
import google.registry.model.server.KmsSecretRevision;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * The {@link KmsUpdater} accumulates updates to a {@link KmsKeyring} and persists them to KMS and
 * Datastore when closed.
 */
public final class KmsUpdater {

  private final KmsConnection kmsConnection;
  private final HashMap<String, byte[]> secretValues;

  @Inject
  public KmsUpdater(@Named("defaultKmsConnection") KmsConnection kmsConnection) {
    this.kmsConnection = kmsConnection;

    // Use LinkedHashMap to preserve insertion order on update() to simplify testing and debugging
    this.secretValues = new LinkedHashMap<>();
  }

  public KmsUpdater setCloudSqlPassword(String password) {
    return setString(password, CLOUD_SQL_PASSWORD_STRING);
  }

  public KmsUpdater setRdeSigningKey(PGPKeyPair keyPair) throws IOException, PGPException {
    return setKeyPair(keyPair, RDE_SIGNING_PRIVATE, RDE_SIGNING_PUBLIC);
  }

  public KmsUpdater setRdeStagingKey(PGPKeyPair keyPair) throws IOException, PGPException {
    return setKeyPair(keyPair, RDE_STAGING_PRIVATE, RDE_STAGING_PUBLIC);
  }

  public KmsUpdater setRdeReceiverPublicKey(PGPPublicKey publicKey) throws IOException {
    return setPublicKey(publicKey, RDE_RECEIVER_PUBLIC);
  }

  public KmsUpdater setBrdaSigningKey(PGPKeyPair keyPair) throws IOException, PGPException {
    return setKeyPair(keyPair, BRDA_SIGNING_PRIVATE, BRDA_SIGNING_PUBLIC);
  }

  public KmsUpdater setBrdaReceiverPublicKey(PGPPublicKey publicKey) throws IOException {
    return setPublicKey(publicKey, BRDA_RECEIVER_PUBLIC);
  }

  public KmsUpdater setRdeSshClientPublicKey(String asciiPublicKey) {
    return setString(asciiPublicKey, RDE_SSH_CLIENT_PUBLIC_STRING);
  }

  public KmsUpdater setRdeSshClientPrivateKey(String asciiPrivateKey) {
    return setString(asciiPrivateKey, RDE_SSH_CLIENT_PRIVATE_STRING);
  }

  public KmsUpdater setSafeBrowsingAPIKey(String apiKey) {
    return setString(apiKey, SAFE_BROWSING_API_KEY);
  }

  public KmsUpdater setToolsCloudSqlPassword(String password) {
    return setString(password, TOOLS_CLOUD_SQL_PASSWORD_STRING);
  }

  public KmsUpdater setIcannReportingPassword(String password) {
    return setString(password, ICANN_REPORTING_PASSWORD_STRING);
  }

  public KmsUpdater setMarksdbDnlLoginAndPassword(String login) {
    return setString(login, MARKSDB_DNL_LOGIN_STRING);
  }

  public KmsUpdater setMarksdbLordnPassword(String password) {
    return setString(password, MARKSDB_LORDN_PASSWORD_STRING);
  }

  public KmsUpdater setMarksdbSmdrlLoginAndPassword(String login) {
    return setString(login, MARKSDB_SMDRL_LOGIN_STRING);
  }

  public KmsUpdater setJsonCredential(String credential) {
    return setString(credential, JSON_CREDENTIAL_STRING);
  }

  /**
   * Generates new encryption keys in KMS, encrypts the updated secrets with them, and persists the
   * encrypted secrets to Datastore.
   *
   * <p>The operations in this method are organized so that existing {@link KmsSecretRevision}
   * entities remain primary and decryptable if a failure occurs.
   */
  public void update() {
    checkState(!secretValues.isEmpty(), "At least one Keyring value must be persisted");

    persistEncryptedValues(encryptValues(secretValues));
  }

  /**
   * Encrypts updated secrets using KMS. If the configured {@code KeyRing} or {@code CryptoKey}
   * associated with a secret doesn't exist, they will first be created.
   *
   * @see google.registry.config.RegistryConfigSettings.Kms
   */
  private ImmutableMap<String, EncryptResponse> encryptValues(Map<String, byte[]> keyValues) {
    ImmutableMap.Builder<String, EncryptResponse> encryptedValues = new ImmutableMap.Builder<>();
    for (Map.Entry<String, byte[]> entry : keyValues.entrySet()) {
      String secretName = entry.getKey();
      encryptedValues.put(secretName, kmsConnection.encrypt(secretName, entry.getValue()));
    }
    return encryptedValues.build();
  }

  private KmsUpdater setString(String key, StringKeyLabel stringKeyLabel) {
    checkArgumentNotNull(key);

    setSecret(stringKeyLabel.getLabel(), KeySerializer.serializeString(key));
    return this;
  }

  private KmsUpdater setPublicKey(PGPPublicKey publicKey, PublicKeyLabel publicKeyLabel)
      throws IOException {
    checkArgumentNotNull(publicKey);

    setSecret(publicKeyLabel.getLabel(), KeySerializer.serializePublicKey(publicKey));
    return this;
  }

  private KmsUpdater setKeyPair(
      PGPKeyPair keyPair, PrivateKeyLabel privateKeyLabel, PublicKeyLabel publicKeyLabel)
      throws IOException, PGPException {
    checkArgumentNotNull(keyPair);

    setSecret(privateKeyLabel.getLabel(), KeySerializer.serializeKeyPair(keyPair));
    setSecret(publicKeyLabel.getLabel(), KeySerializer.serializePublicKey(keyPair.getPublicKey()));
    return this;
  }

  /**
   * Persists encrypted secrets to Datastore as {@link KmsSecretRevision} entities and makes them
   * primary. {@link KmsSecret} entities point to the latest {@link KmsSecretRevision}.
   *
   * <p>The changes are committed transactionally; if an error occurs, all existing {@link
   * KmsSecretRevision} entities will remain primary.
   */
  private static void persistEncryptedValues(
      final ImmutableMap<String, EncryptResponse> encryptedValues) {
    tm()
        .transact(
            () -> {
              for (Map.Entry<String, EncryptResponse> entry : encryptedValues.entrySet()) {
                String secretName = entry.getKey();
                EncryptResponse revisionData = entry.getValue();

                KmsSecretRevision secretRevision =
                    new KmsSecretRevision.Builder()
                        .setEncryptedValue(revisionData.ciphertext())
                        .setKmsCryptoKeyVersionName(revisionData.cryptoKeyVersionName())
                        .setParent(secretName)
                        .build();
                ofy().save().entities(secretRevision, KmsSecret.create(secretName, secretRevision));
              }
            });
  }

  private void setSecret(String secretName, byte[] value) {
    checkArgument(!secretValues.containsKey(secretName), "Attempted to set %s twice", secretName);
    secretValues.put(secretName, value);
  }
}
