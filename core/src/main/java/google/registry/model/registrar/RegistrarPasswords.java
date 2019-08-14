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

package google.registry.model.registrar;

import static com.google.common.io.BaseEncoding.base64;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Supplier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Common utility class to handle password hashing and salting */
public final class RegistrarPasswords {

  static final Supplier<byte[]> SALT_SUPPLIER =
      () -> {
        // There are 32 bytes in a sha-256 hash, and the salt should generally be the same size.
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return salt;
      };

  static String hashPassword(String password, String salt) {
    try {
      return base64()
          .encode(MessageDigest.getInstance("SHA-256").digest((password + salt).getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // All implementations of MessageDigest are required to support SHA-256.
      throw new RuntimeException(e);
    }
  }
}
