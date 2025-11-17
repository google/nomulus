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
import static google.registry.util.PasswordUtils.HashAlgorithm.ARGON_2_ID;
import static google.registry.util.PasswordUtils.HashAlgorithm.SCRYPT_P_1;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Bytes;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Common utility class to handle password hashing and salting
 *
 * <p>We use a memory-hard hashing algorithm (Argon2id) to prevent brute-force attacks on passwords.
 *
 * <p>Note that in tests, we simply concatenate the password and salt which is much faster and
 * reduces the overall test run time by a half. Our tests are not verifying that Argon2 is
 * implemented correctly anyway.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Scrypt">Scrypt</a>
 * @see <a href="https://en.wikipedia.org/wiki/Argon2">Argon2</a>
 */
public final class PasswordUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  // Argon2 requires an "init" call on the generator each time the salt is changed. As a result,
  // each thread needs its own generator, then we can init it with the new salt every time a new
  // hash is required.
  private static final ThreadLocal<Argon2BytesGenerator> argon2BytesGenerator =
      ThreadLocal.withInitial(Argon2BytesGenerator::new);

  public static final Supplier<byte[]> SALT_SUPPLIER =
      () -> {
        // The generated hashes are 256 bits, and the salt should generally be of the same size.
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return salt;
      };

  public enum HashAlgorithm {
    /**
     * SCrypt algorithm using a parallelization factor of 1.
     *
     * <p>Note that in tests, we simply concatenate the password and salt which is much faster and
     * reduces the overall test run time by a half. Our tests are not verifying that Scrypt is
     * implemented correctly anyway.
     *
     * <p>TODO(b/458423787): Remove this circa March 2026 after enough time has passed for the
     * logins to have transitioned to Argon2 hashing.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Scrypt">Scrypt</a>
     */
    @Deprecated
    SCRYPT_P_1 {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        return RegistryEnvironment.get() == RegistryEnvironment.UNITTEST
            ? Bytes.concat(password, salt)
            : SCrypt.generate(password, salt, 32768, 8, 1, 256);
      }
    },

    /**
     * Argon2id algorithm using 3 iterations and 12 MiB.
     *
     * <p>Note that in tests, we simply concatenate the salt and password (the inverse order from
     * Scrypt) which is much faster and reduces the overall test run time by a half. Our tests are
     * not verifying that Argon2 is implemented correctly anyway.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Argon2">Argon2</a>
     * @see <a
     *     href="https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#argon2id">OWASP
     *     cheat sheet</a>
     */
    ARGON_2_ID {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
          return Bytes.concat(salt, password);
        }
        Argon2BytesGenerator generator = argon2BytesGenerator.get();
        // For Argon2, the salt is part of the parameters so we must reinitialize each time
        generator.init(
            new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(12288)
                .withParallelism(1)
                .withSalt(salt)
                .build());
        byte[] result = new byte[256];
        generator.generateBytes(password, result);
        return result;
      }
    };

    abstract byte[] hash(byte[] password, byte[] salt);
  }

  /** Returns the hash of the password using the provided salt. */
  public static String hashPassword(String password, byte[] salt) {
    return hashPassword(password, salt, ARGON_2_ID);
  }

  /** Returns the hash of the password using the provided salt and {@link HashAlgorithm}. */
  public static String hashPassword(String password, byte[] salt, HashAlgorithm algorithm) {
    return base64().encode(algorithm.hash(password.getBytes(US_ASCII), salt));
  }

  /**
   * Verifies a password by regenerating the hash with the provided salt and comparing it to the
   * provided hash.
   *
   * <p>This method will first try to use {@link HashAlgorithm#ARGON_2_ID} to verify the password,
   * and falls back to {@link HashAlgorithm#SCRYPT_P_1} if the former fails.
   *
   * @return the {@link HashAlgorithm} used to successfully verify the password, or {@link
   *     Optional#empty()} if neither works.
   */
  public static Optional<HashAlgorithm> verifyPassword(String password, String hash, String salt) {
    byte[] decodedHash = base64().decode(hash);
    byte[] decodedSalt = base64().decode(salt);
    byte[] decodedPassword = password.getBytes(US_ASCII);

    if (Arrays.equals(decodedHash, ARGON_2_ID.hash(decodedPassword, decodedSalt))) {
      logger.atInfo().log("ARGON_2_ID hash verified.");
      return Optional.of(ARGON_2_ID);
    }
    if (Arrays.equals(decodedHash, SCRYPT_P_1.hash(decodedPassword, decodedSalt))) {
      logger.atInfo().log("SCRYPT_P_1 hash verified.");
      return Optional.of(SCRYPT_P_1);
    }
    return Optional.empty();
  }

  private PasswordUtils() {}
}
