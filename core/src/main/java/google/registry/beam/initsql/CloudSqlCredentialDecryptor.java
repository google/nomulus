// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.services.cloudkms.v1.model.DecryptRequest;
import com.google.common.base.Strings;
import google.registry.keyring.kms.KmsConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.inject.Inject;

/**
 * Decrypts data using Cloud KMS, with the same crypto key with which Cloud SQL credential files on
 * GCS was encrypted. See {@link BackupPaths#getCloudSQLCredentialFilePatterns} for more
 * information.
 */
public class CloudSqlCredentialDecryptor {

  private static final String CRYPTO_KEY_NAME = "nomulus-tool-key";
  private final KmsConnection kmsConnection;

  @Inject
  CloudSqlCredentialDecryptor(KmsConnection kmsConnection) {
    this.kmsConnection = kmsConnection;
  }

  public String decrypt(String data) {
    checkArgument(!Strings.isNullOrEmpty(data), "Null or empty data.");
    byte[] ciphertext = Base64.getDecoder().decode(data);
    // Re-encode for Cloud KMS JSON REST API, invoked through kmsConnection.
    String urlSafeCipherText = new DecryptRequest().encodeCiphertext(ciphertext).getCiphertext();
    return new String(
        kmsConnection.decrypt(CRYPTO_KEY_NAME, urlSafeCipherText), StandardCharsets.UTF_8);
  }
}
