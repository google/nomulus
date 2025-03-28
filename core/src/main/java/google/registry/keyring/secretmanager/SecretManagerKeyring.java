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

import static com.google.common.base.CaseFormat.LOWER_HYPHEN;
import static com.google.common.base.CaseFormat.UPPER_UNDERSCORE;

import google.registry.keyring.api.KeySerializer;
import google.registry.keyring.api.Keyring;
import google.registry.keyring.api.KeyringException;
import google.registry.privileges.secretmanager.KeyringSecretStore;
import jakarta.inject.Inject;
import java.io.IOException;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;

/** A {@link Keyring} implementation which stores sensitive data in the Secret Manager. */
public class SecretManagerKeyring implements Keyring {

  /** Key labels for private key secrets. */
  enum PrivateKeyLabel {
    BRDA_SIGNING_PRIVATE,
    RDE_SIGNING_PRIVATE,
    RDE_STAGING_PRIVATE;

    String getLabel() {
      return UPPER_UNDERSCORE.to(LOWER_HYPHEN, name());
    }
  }

  /** Key labels for public key secrets. */
  enum PublicKeyLabel {
    BRDA_RECEIVER_PUBLIC,
    BRDA_SIGNING_PUBLIC,
    RDE_RECEIVER_PUBLIC,
    RDE_SIGNING_PUBLIC,
    RDE_STAGING_PUBLIC;

    String getLabel() {
      return UPPER_UNDERSCORE.to(LOWER_HYPHEN, name());
    }
  }

  /** Key labels for string secrets. */
  enum StringKeyLabel {
    BSA_API_KEY_STRING,
    ICANN_REPORTING_PASSWORD_STRING,
    MARKSDB_DNL_LOGIN_STRING,
    MARKSDB_LORDN_PASSWORD_STRING,
    MARKSDB_SMDRL_LOGIN_STRING,
    RDE_SSH_CLIENT_PRIVATE_STRING,
    RDE_SSH_CLIENT_PUBLIC_STRING,
    SAFE_BROWSING_API_KEY,
    SQL_PRIMARY_CONN_NAME,
    SQL_REPLICA_CONN_NAME;

    String getLabel() {
      return UPPER_UNDERSCORE.to(LOWER_HYPHEN, name());
    }
  }

  private final KeyringSecretStore secretStore;

  @Inject
  SecretManagerKeyring(KeyringSecretStore secretStore) {
    this.secretStore = secretStore;
  }

  @Override
  public PGPKeyPair getRdeSigningKey() {
    return getKeyPair(PrivateKeyLabel.RDE_SIGNING_PRIVATE);
  }

  @Override
  public PGPPublicKey getRdeStagingEncryptionKey() {
    return getPublicKey(PublicKeyLabel.RDE_STAGING_PUBLIC);
  }

  @Override
  public PGPPrivateKey getRdeStagingDecryptionKey() {
    return getPrivateKey(PrivateKeyLabel.RDE_STAGING_PRIVATE);
  }

  @Override
  public PGPPublicKey getRdeReceiverKey() {
    return getPublicKey(PublicKeyLabel.RDE_RECEIVER_PUBLIC);
  }

  @Override
  public PGPKeyPair getBrdaSigningKey() {
    return getKeyPair(PrivateKeyLabel.BRDA_SIGNING_PRIVATE);
  }

  @Override
  public PGPPublicKey getBrdaReceiverKey() {
    return getPublicKey(PublicKeyLabel.BRDA_RECEIVER_PUBLIC);
  }

  @Override
  public String getRdeSshClientPublicKey() {
    return getString(StringKeyLabel.RDE_SSH_CLIENT_PUBLIC_STRING);
  }

  @Override
  public String getRdeSshClientPrivateKey() {
    return getString(StringKeyLabel.RDE_SSH_CLIENT_PRIVATE_STRING);
  }

  @Override
  public String getSafeBrowsingAPIKey() {
    return getString(StringKeyLabel.SAFE_BROWSING_API_KEY);
  }

  @Override
  public String getIcannReportingPassword() {
    return getString(StringKeyLabel.ICANN_REPORTING_PASSWORD_STRING);
  }

  @Override
  public String getMarksdbDnlLoginAndPassword() {
    return getString(StringKeyLabel.MARKSDB_DNL_LOGIN_STRING);
  }

  @Override
  public String getMarksdbLordnPassword() {
    return getString(StringKeyLabel.MARKSDB_LORDN_PASSWORD_STRING);
  }

  @Override
  public String getMarksdbSmdrlLoginAndPassword() {
    return getString(StringKeyLabel.MARKSDB_SMDRL_LOGIN_STRING);
  }

  @Override
  public String getBsaApiKey() {
    return getString(StringKeyLabel.BSA_API_KEY_STRING);
  }

  @Override
  public String getSqlPrimaryConnectionName() {
    return getString(StringKeyLabel.SQL_PRIMARY_CONN_NAME);
  }

  @Override
  public String getSqlReplicaConnectionName() {
    return getString(StringKeyLabel.SQL_REPLICA_CONN_NAME);
  }

  /** No persistent resources are maintained for this Keyring implementation. */
  @Override
  public void close() {}

  private String getString(StringKeyLabel keyLabel) {
    return KeySerializer.deserializeString(getDecryptedData(keyLabel.getLabel()));
  }

  private PGPKeyPair getKeyPair(PrivateKeyLabel keyLabel) {
    try {
      return KeySerializer.deserializeKeyPair(getDecryptedData(keyLabel.getLabel()));
    } catch (IOException | PGPException e) {
      throw new KeyringException(
          String.format("Could not parse private keyLabel %s", keyLabel), e);
    }
  }

  private PGPPublicKey getPublicKey(PublicKeyLabel keyLabel) {
    try {
      return KeySerializer.deserializePublicKey(getDecryptedData(keyLabel.getLabel()));
    } catch (IOException e) {
      throw new KeyringException(String.format("Could not parse public keyLabel %s", keyLabel), e);
    }
  }

  private PGPPrivateKey getPrivateKey(PrivateKeyLabel keyLabel) {
    return getKeyPair(keyLabel).getPrivateKey();
  }

  private byte[] getDecryptedData(String keyName) {
    try {
      return secretStore.getSecret(keyName);
    } catch (Exception e) {
      throw new KeyringException("Failed to retrieve secret for " + keyName, e);
    }
  }
}
