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

import google.registry.keyring.api.KeyringException;

/** An abstraction to simplify Cloud KMS operations. */
public interface KmsConnection {

  /**
   * The maximum allowable secret size, as set by Cloud KMS.
   *
   * @see <a
   *     href="https://cloud.google.com/kms/docs/reference/rest/v1/projects.locations.keyRings.cryptoKeys/encrypt#request-body">projects.locations.keyRings.cryptoKeys.encrypt</a>
   */
  int MAX_SECRET_SIZE_BYTES = 64 * 1024;

  /**
   * Encrypts a plaintext with CryptoKey {@code cryptoKeyName} on KeyRing {@code keyRingName}.
   *
   * <p>The latest CryptoKeyVersion is used to encrypt the value. The value must not be larger than
   * {@code MAX_SECRET_SIZE_BYTES}.
   *
   * <p>If no applicable CryptoKey or CryptoKeyVersion exist, they will be created.
   *
   * @throws KeyringException on encryption failure.
   */
  EncryptResponse encrypt(String cryptoKeyName, byte[] plaintext);

  /**
   * Decrypts a Cloud KMS encrypted and encoded value with CryptoKey {@code cryptoKeyName}.
   *
   * @throws KeyringException on decryption failure.
   */
  byte[] decrypt(String cryptoKeyName, String encodedCiphertext);
}
