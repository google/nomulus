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

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.services.cloudkms.v1.CloudKMS;
import com.google.api.services.cloudkms.v1.model.CryptoKey;
import com.google.api.services.cloudkms.v1.model.CryptoKeyVersion;
import com.google.api.services.cloudkms.v1.model.DecryptRequest;
import com.google.api.services.cloudkms.v1.model.EncryptRequest;
import com.google.api.services.cloudkms.v1.model.KeyRing;
import com.google.api.services.cloudkms.v1.model.UpdateCryptoKeyPrimaryVersionRequest;
import google.registry.keyring.api.KeyringException;
import google.registry.util.Retrier;
import java.io.IOException;

/** The {@link KmsConnection} which talks to Cloud KMS. */
class KmsConnectionImpl implements KmsConnection {

  private static final String KMS_LOCATION_FORMAT = "projects/%s/locations/global";
  private static final String KMS_KEYRING_NAME_FORMAT = "projects/%s/locations/global/keyRings/%s";
  private static final String KMS_CRYPTO_KEY_NAME_FORMAT =
      "projects/%s/locations/global/keyRings/%s/cryptoKeys/%s";

  private final CloudKMS kms;
  private final String kmsKeyRingName;
  private final String projectId;
  private final Retrier retrier;

  KmsConnectionImpl(String projectId, String kmsKeyRingName, Retrier retrier, CloudKMS kms) {
    this.projectId = projectId;
    this.kmsKeyRingName = kmsKeyRingName;
    this.retrier = retrier;
    this.kms = kms;
  }

  @Override
  public EncryptResponse encrypt(String cryptoKeyName, byte[] value) {
    checkArgument(
        value.length <= MAX_SECRET_SIZE_BYTES,
        "Value to encrypt was larger than %s bytes",
        MAX_SECRET_SIZE_BYTES);
    try {
      return attemptEncrypt(cryptoKeyName, value);
    } catch (IOException e) {
      throw new KeyringException(
          String.format("CloudKMS encrypt operation failed for secret %s", cryptoKeyName), e);
    }
  }

  private EncryptResponse attemptEncrypt(String cryptoKeyName, byte[] value) throws IOException {
    String fullKeyRingName = getKeyRingName(projectId, kmsKeyRingName);
    try {
      kms.projects().locations().keyRings().get(fullKeyRingName).execute();
    } catch (GoogleJsonResponseException jsonException) {
      if (jsonException.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
        // Create the KeyRing in the "global" namespace. Encryption keys will be accessible from all
        // GCP regions.
        kms.projects()
            .locations()
            .keyRings()
            .create(getLocationName(projectId), new KeyRing())
            .setKeyRingId(kmsKeyRingName)
            .execute();
      } else {
        throw jsonException;
      }
    }

    String fullKeyName = getCryptoKeyName(projectId, kmsKeyRingName, cryptoKeyName);

    boolean newCryptoKey = false;
    try {
      kms.projects().locations().keyRings().cryptoKeys().get(fullKeyName).execute();
    } catch (GoogleJsonResponseException jsonException) {
      if (jsonException.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
        newCryptoKey = true;
        kms.projects()
            .locations()
            .keyRings()
            .cryptoKeys()
            .create(fullKeyRingName, new CryptoKey().setPurpose("ENCRYPT_DECRYPT"))
            .setCryptoKeyId(cryptoKeyName)
            .execute();
      } else {
        throw jsonException;
      }
    }

    // New CryptoKeys start with a CryptoKeyVersion, so we only create a new CryptoKeyVersion and
    // rotate to it if we're dealing with an existing CryptoKey.
    if (!newCryptoKey) {
      CryptoKeyVersion cryptoKeyVersion =
          kms.projects()
              .locations()
              .keyRings()
              .cryptoKeys()
              .cryptoKeyVersions()
              .create(fullKeyName, new CryptoKeyVersion())
              .execute();

      kms.projects()
          .locations()
          .keyRings()
          .cryptoKeys()
          .updatePrimaryVersion(
              fullKeyName,
              new UpdateCryptoKeyPrimaryVersionRequest()
                  .setCryptoKeyVersionId(getCryptoKeyVersionId(cryptoKeyVersion)))
          .execute();
    }

    return EncryptResponse.create(
        kms.projects()
            .locations()
            .keyRings()
            .cryptoKeys()
            .encrypt(fullKeyName, new EncryptRequest().encodePlaintext(value))
            .execute());
  }

  @Override
  public byte[] decrypt(final String cryptoKeyName, final String encodedCiphertext) {
    try {
      return retrier.callWithRetry(
          () -> attemptDecrypt(cryptoKeyName, encodedCiphertext), IOException.class);
    } catch (RuntimeException e) {
      throw new KeyringException(
          String.format("CloudKMS decrypt operation failed for secret %s", cryptoKeyName), e);
    }
  }

  private byte[] attemptDecrypt(String cryptoKeyName, String encodedCiphertext) throws IOException {
    return kms.projects()
        .locations()
        .keyRings()
        .cryptoKeys()
        .decrypt(
            getCryptoKeyName(projectId, kmsKeyRingName, cryptoKeyName),
            new DecryptRequest().setCiphertext(encodedCiphertext))
        .execute()
        .decodePlaintext();
  }

  private static String getLocationName(String projectId) {
    return String.format(KMS_LOCATION_FORMAT, projectId);
  }

  private static String getKeyRingName(String projectId, String kmsKeyRingName) {
    return String.format(KMS_KEYRING_NAME_FORMAT, projectId, kmsKeyRingName);
  }

  private static String getCryptoKeyName(
      String projectId, String kmsKeyRingName, String cryptoKeyName) {
    return String.format(KMS_CRYPTO_KEY_NAME_FORMAT, projectId, kmsKeyRingName, cryptoKeyName);
  }

  private static String getCryptoKeyVersionId(CryptoKeyVersion cryptoKeyVersion) {
    String cryptoKeyVersionName = cryptoKeyVersion.getName();
    return cryptoKeyVersionName.substring(cryptoKeyVersionName.lastIndexOf('/') + 1);
  }
}
