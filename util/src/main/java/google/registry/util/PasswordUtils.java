// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Supplier;
import com.google.common.primitives.Bytes;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.SCrypt;

/**
 * Common utility class to handle password hashing and salting /*
 *
 * <p>We use a memory-hard hashing algorithm (Scrypt) to prevent brute-force attacks on passwords.
 *
 * <p>Note that in tests, we simply concatenate the password and salt which is much faster and
 * reduces the overall test run time by a half. Our tests are not verifying that SCRYPT is
 * implemented correctly anyway.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Scrypt">Scrypt</a>
 */
public final class PasswordUtils {

  private PasswordUtils() {}

  public static final Supplier<byte[]> SALT_SUPPLIER =
      () -> {
        // The generated hashes are 256 bits, and the salt should generally be of the same size.
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return salt;
      };

  private static byte[] hashPassword(byte[] password, byte[] salt) {
    return RegistryEnvironment.get() == RegistryEnvironment.UNITTEST
        ? Bytes.concat(password, salt)
        : SCrypt.generate(password, salt, 32768, 8, 1, 256);
  }

  /** Returns the hash of the password using the provided salt. */
  public static String hashPassword(String password, byte[] salt) {
    return base64().encode(hashPassword(password.getBytes(US_ASCII), salt));
  }

  /**
   * Verifies a password by regenerating the hash with the provided salt and comparing it to the
   * provided hash.
   */
  public static boolean verifyPassword(String password, String hash, String salt) {
    byte[] decodedHash = base64().decode(hash);
    byte[] decodedSalt = base64().decode(salt);
    byte[] calculatedHash = hashPassword(password.getBytes(US_ASCII), decodedSalt);
    return Arrays.equals(decodedHash, calculatedHash);
  }
}
