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

package google.registry.keyring.secretmanager;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PrivateKeyLabel.BRDA_SIGNING_PRIVATE;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PrivateKeyLabel.RDE_SIGNING_PRIVATE;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PrivateKeyLabel.RDE_STAGING_PRIVATE;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel.BRDA_RECEIVER_PUBLIC;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel.BRDA_SIGNING_PUBLIC;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel.RDE_RECEIVER_PUBLIC;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel.RDE_SIGNING_PUBLIC;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel.RDE_STAGING_PUBLIC;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.BSA_API_KEY_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.ICANN_REPORTING_PASSWORD_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.MARKSDB_DNL_LOGIN_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.MARKSDB_LORDN_PASSWORD_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.MARKSDB_SMDRL_LOGIN_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.RDE_SSH_CLIENT_PRIVATE_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.RDE_SSH_CLIENT_PUBLIC_STRING;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.SAFE_BROWSING_API_KEY;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.SQL_PRIMARY_CONN_NAME;
import static google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel.SQL_REPLICA_CONN_NAME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.flogger.FluentLogger;
import google.registry.keyring.api.KeySerializer;
import google.registry.keyring.secretmanager.SecretManagerKeyring.PrivateKeyLabel;
import google.registry.keyring.secretmanager.SecretManagerKeyring.PublicKeyLabel;
import google.registry.keyring.secretmanager.SecretManagerKeyring.StringKeyLabel;
import google.registry.privileges.secretmanager.KeyringSecretStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * The {@link SecretManagerKeyringUpdater} accumulates updates to a {@link SecretManagerKeyring} and
 * persists them to Cloud Secret Manager when closed.
 */
public final class SecretManagerKeyringUpdater {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final KeyringSecretStore secretStore;
  private final HashMap<String, byte[]> secretValues;

  @Inject
  public SecretManagerKeyringUpdater(KeyringSecretStore secretStore) {
    this.secretStore = secretStore;

    // Use LinkedHashMap to preserve insertion order on update() to simplify testing and debugging
    this.secretValues = new LinkedHashMap<>();
  }

  public SecretManagerKeyringUpdater setRdeSigningKey(PGPKeyPair keyPair)
      throws IOException, PGPException {
    return setKeyPair(keyPair, RDE_SIGNING_PRIVATE, RDE_SIGNING_PUBLIC);
  }

  public SecretManagerKeyringUpdater setRdeStagingKey(PGPKeyPair keyPair)
      throws IOException, PGPException {
    return setKeyPair(keyPair, RDE_STAGING_PRIVATE, RDE_STAGING_PUBLIC);
  }

  public SecretManagerKeyringUpdater setRdeReceiverPublicKey(PGPPublicKey publicKey)
      throws IOException {
    return setPublicKey(publicKey, RDE_RECEIVER_PUBLIC);
  }

  public SecretManagerKeyringUpdater setBrdaSigningKey(PGPKeyPair keyPair)
      throws IOException, PGPException {
    return setKeyPair(keyPair, BRDA_SIGNING_PRIVATE, BRDA_SIGNING_PUBLIC);
  }

  public SecretManagerKeyringUpdater setBrdaReceiverPublicKey(PGPPublicKey publicKey)
      throws IOException {
    return setPublicKey(publicKey, BRDA_RECEIVER_PUBLIC);
  }

  public SecretManagerKeyringUpdater setRdeSshClientPublicKey(String asciiPublicKey) {
    return setString(asciiPublicKey, RDE_SSH_CLIENT_PUBLIC_STRING);
  }

  public SecretManagerKeyringUpdater setRdeSshClientPrivateKey(String asciiPrivateKey) {
    return setString(asciiPrivateKey, RDE_SSH_CLIENT_PRIVATE_STRING);
  }

  public SecretManagerKeyringUpdater setSafeBrowsingAPIKey(String apiKey) {
    return setString(apiKey, SAFE_BROWSING_API_KEY);
  }

  public SecretManagerKeyringUpdater setIcannReportingPassword(String password) {
    return setString(password, ICANN_REPORTING_PASSWORD_STRING);
  }

  public SecretManagerKeyringUpdater setMarksdbDnlLoginAndPassword(String login) {
    return setString(login, MARKSDB_DNL_LOGIN_STRING);
  }

  public SecretManagerKeyringUpdater setMarksdbLordnPassword(String password) {
    return setString(password, MARKSDB_LORDN_PASSWORD_STRING);
  }

  public SecretManagerKeyringUpdater setMarksdbSmdrlLoginAndPassword(String login) {
    return setString(login, MARKSDB_SMDRL_LOGIN_STRING);
  }

  public SecretManagerKeyringUpdater setBsaApiKey(String credential) {
    return setString(credential, BSA_API_KEY_STRING);
  }

  public SecretManagerKeyringUpdater setSqlPrimaryConnectionName(String name) {
    return setString(name, SQL_PRIMARY_CONN_NAME);
  }

  public SecretManagerKeyringUpdater setSqlReplicaConnectionName(String name) {
    return setString(name, SQL_REPLICA_CONN_NAME);
  }

  /**
   * Persists the secrets in the Secret Manager.
   *
   * <p>Updates to the Secret Manager are not transactional. If an error happens, the successful
   * updates are not reverted; unwritten updates are aborted. This is not a problem right now, since
   * this class is only used by the {@code UpdateKmsKeyringCommand}, which is invoked manually and
   * only updates one secret at a time.
   */
  public void update() {
    checkState(!secretValues.isEmpty(), "At least one Keyring value must be persisted");

    try {
      for (Map.Entry<String, byte[]> e : secretValues.entrySet()) {
        secretStore.createOrUpdateSecret(e.getKey(), e.getValue());
        logger.atInfo().log("Secret %s updated.", e.getKey());
      }
    } catch (RuntimeException e) {
      throw new RuntimeException(
          "Failed to persist secrets to Secret Manager. "
              + "Please check the status of Secret Manager and re-run the command.",
          e);
    }
  }

  private SecretManagerKeyringUpdater setString(String key, StringKeyLabel stringKeyLabel) {
    checkArgumentNotNull(key);

    setSecret(stringKeyLabel.getLabel(), KeySerializer.serializeString(key));
    return this;
  }

  private SecretManagerKeyringUpdater setPublicKey(
      PGPPublicKey publicKey, PublicKeyLabel publicKeyLabel) throws IOException {
    checkArgumentNotNull(publicKey);

    setSecret(publicKeyLabel.getLabel(), KeySerializer.serializePublicKey(publicKey));
    return this;
  }

  private SecretManagerKeyringUpdater setKeyPair(
      PGPKeyPair keyPair, PrivateKeyLabel privateKeyLabel, PublicKeyLabel publicKeyLabel)
      throws IOException, PGPException {
    checkArgumentNotNull(keyPair);

    setSecret(privateKeyLabel.getLabel(), KeySerializer.serializeKeyPair(keyPair));
    setSecret(publicKeyLabel.getLabel(), KeySerializer.serializePublicKey(keyPair.getPublicKey()));
    return this;
  }

  private void setSecret(String secretName, byte[] value) {
    checkArgument(!secretValues.containsKey(secretName), "Attempted to set %s twice", secretName);
    secretValues.put(secretName, value);
  }
}
