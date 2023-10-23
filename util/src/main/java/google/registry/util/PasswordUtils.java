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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.annotation.Nullable;
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

  public enum HashAlgorithm {
    SHA256 {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        return SHA256_DIGEST_SUPPLIER
            .get()
            .digest((new String(password, US_ASCII) + base64().encode(salt)).getBytes(US_ASCII));
      }
    },
    SCRYPT {
      @Override
      byte[] hash(byte[] password, byte[] salt) {
        return SCrypt.generate(password, salt, 32768, 8, 1, 256);
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

  public static String hashPassword(String password, byte[] salt, HashAlgorithm algorithm) {
    return base64().encode(algorithm.hash(password.getBytes(US_ASCII), salt));
  }

  @Nullable
  public static HashAlgorithm verifyPassword(String password, String hash, String salt) {
    byte[] decodedHash = base64().decode(hash);
    byte[] decodedSalt = base64().decode(salt);
    byte[] calculatedHash = SCRYPT.hash(password.getBytes(US_ASCII), decodedSalt);
    if (Arrays.equals(decodedHash, calculatedHash)) {
      logger.atInfo().log("Scrypt hash verified.");
      return SCRYPT;
    }
    calculatedHash = SHA256.hash(password.getBytes(US_ASCII), decodedSalt);
    if (Arrays.equals(decodedHash, calculatedHash)) {
      logger.atInfo().log("SHA256 hash verified.");
      return SHA256;
    }
    return null;
  }
}
