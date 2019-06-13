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

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;

/**
 * A value type class containing a Cloud KMS encrypted and encoded ciphertext, and the name of the
 * CryptoKeyVersion used to encrypt it.
 */
@AutoValue
abstract class EncryptResponse {

  static EncryptResponse create(
      com.google.api.services.cloudkms.v1.model.EncryptResponse cloudKmsEncryptResponse) {
    return new AutoValue_EncryptResponse(
        cloudKmsEncryptResponse.getCiphertext(), cloudKmsEncryptResponse.getName());
  }

  @VisibleForTesting
  static EncryptResponse create(String ciphertext, String cryptoKeyVersionName) {
    return new AutoValue_EncryptResponse(ciphertext, cryptoKeyVersionName);
  }

  abstract String ciphertext();

  abstract String cryptoKeyVersionName();
}
