// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.io.BaseEncoding.base64;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.PasswordUtils.HashAlgorithm.ARGON_2_ID;
import static google.registry.util.PasswordUtils.HashAlgorithm.SCRYPT_P_1;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static google.registry.util.PasswordUtils.verifyPassword;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PasswordUtils}. */
final class PasswordUtilsTest {

  @Test
  void testDifferentSalts() {
    byte[] first = SALT_SUPPLIER.get();
    byte[] second = SALT_SUPPLIER.get();
    assertThat(first.length).isEqualTo(32);
    assertThat(second.length).isEqualTo(32);
    assertThat(Arrays.equals(first, second)).isFalse();
  }

  @Test
  void testHash() {
    byte[] salt = SALT_SUPPLIER.get();
    String password = "mySuperSecurePassword";
    String hashedPassword = hashPassword(password, salt);
    assertThat(hashedPassword).isEqualTo(hashPassword(password, salt));
    assertThat(hashedPassword).isNotEqualTo(hashPassword(password + "a", salt));
    byte[] secondSalt = SALT_SUPPLIER.get();
    assertThat(hashedPassword).isNotEqualTo(hashPassword(password, secondSalt));
  }

  @Test
  void testVerify_argon2_default() {
    byte[] salt = SALT_SUPPLIER.get();
    String password = "mySuperSecurePassword";
    String hashedPassword = hashPassword(password, salt);
    assertThat(hashedPassword).isEqualTo(hashPassword(password, salt, ARGON_2_ID));
    assertThat(verifyPassword(password, hashedPassword, base64().encode(salt)))
        .hasValue(ARGON_2_ID);
  }

  @Test
  void testVerify_scrypt() {
    byte[] salt = SALT_SUPPLIER.get();
    String password = "mySuperSecurePassword";
    String hashedPassword = hashPassword(password, salt, SCRYPT_P_1);
    assertThat(verifyPassword(password, hashedPassword, base64().encode(salt)))
        .hasValue(SCRYPT_P_1);
  }

  @Test
  void testVerify_failure() {
    byte[] salt = SALT_SUPPLIER.get();
    String password = "mySuperSecurePassword";
    String hashedPassword = hashPassword(password, salt);
    assertThat(verifyPassword(password + "a", hashedPassword, base64().encode(salt))).isEmpty();
  }
}
