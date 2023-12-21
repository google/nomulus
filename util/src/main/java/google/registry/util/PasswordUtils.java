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
import static google.registry.util.PasswordUtils.HashAlgorithm.SCRYPT;
import static google.registry.util.PasswordUtils.HashAlgorithm.SHA256;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Bytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import org.bouncycastle.crypto.generators.SCrypt;

/** Common utility class to handle password hashing and salting */
public final class PasswordUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Supplier<MessageDigest> SHA256_DIGEST_SUPPLIER =
      Suppliers.memoize(
          () -> {
            try {
              return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
              // All implementations of MessageDigest are required to support SHA-256.
              throw new RuntimeException(
                  "All MessageDigest implementations are required to support SHA-256 but this one"
                      + " didn't",
                  e);
            }
          });

  private PasswordUtils() {}

  /**
   * Password hashing algorithm that takes a password and a salt (both as {@code byte[]}) and
   * returns a hash.
   */
  public enum HashAlgorithm {
    /**
     * SHA-2 that returns a 256-bit digest.
     *
     * @see <a href="https://en.wikipedia.org/wiki/SHA-2">SHA-2</a>
     */
    @Deprecated
    SHA256 {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        return SHA256_DIGEST_SUPPLIER
            .get()
            .digest((new String(password, US_ASCII) + base64().encode(salt)).getBytes(US_ASCII));
      }
    },

    /**
     * Memory-hard hashing algorithm, preferred over SHA-256.
     *
     * <p>Note that in tests, we simply concatenate the password and salt which is much faster and
     * reduces the overall test run time by a half. Our tests are not verifying that SCRYPT is
     * implemented correctly anyway.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Scrypt">Scrypt</a>
     */
    SCRYPT {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        return RegistryEnvironment.get() == RegistryEnvironment.UNITTEST
            ? Bytes.concat(password, salt)
            : SCrypt.generate(password, salt, 32768, 8, 1, 256);
      }
    };

    abstract byte[] hash(byte[] password, byte[] salt);
  }

  public static final Supplier<byte[]> SALT_SUPPLIER =
      () -> {
        // The generated hashes are 256 bits, and the salt should generally be of the same size.
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return salt;
      };

  public static String hashPassword(String password, byte[] salt) {
    return hashPassword(password, salt, SCRYPT);
  }

  /** Returns the hash of the password using the provided salt and {@link HashAlgorithm}. */
  public static String hashPassword(String password, byte[] salt, HashAlgorithm algorithm) {
    return base64().encode(algorithm.hash(password.getBytes(US_ASCII), salt));
  }

  /**
   * Verifies a password by regenerating the hash with the provided salt and comparing it to the
   * provided hash.
   *
   * <p>This method will first try to use {@link HashAlgorithm#SCRYPT} to verify the password, and
   * falls back to {@link HashAlgorithm#SHA256} if the former fails.
   *
   * @return the {@link HashAlgorithm} used to successfully verify the password, or {@link
   *     Optional#empty()} if neither works.
   */
  public static Optional<HashAlgorithm> verifyPassword(String password, String hash, String salt) {
    byte[] decodedHash = base64().decode(hash);
    byte[] decodedSalt = base64().decode(salt);
    byte[] calculatedHash = SCRYPT.hash(password.getBytes(US_ASCII), decodedSalt);
    if (Arrays.equals(decodedHash, calculatedHash)) {
      logger.atInfo().log("Scrypt hash verified.");
      return Optional.of(SCRYPT);
    }
    calculatedHash = SHA256.hash(password.getBytes(US_ASCII), decodedSalt);
    if (Arrays.equals(decodedHash, calculatedHash)) {
      logger.atInfo().log("SHA256 hash verified.");
      return Optional.of(SHA256);
    }
    return Optional.empty();
  }
}
