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

package google.registry.flows;

import static google.registry.model.common.FeatureFlag.FeatureName.USE_RANDOM_SERVER_TRID;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;
import google.registry.model.common.FeatureFlag;
import jakarta.inject.Inject;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/** A server Trid provider that generates transaction IDs. */
public class ServerTridProviderImpl implements ServerTridProvider {
  private static final AtomicLong idCounter = new AtomicLong();

  @VisibleForTesting
  static final ThreadLocal<SecureRandom> secureRandom = ThreadLocal.withInitial(
      () -> {
        try {
          return SecureRandom.getInstance("DRBG");
        } catch (NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
      });

  @Inject
  public ServerTridProviderImpl() {
  }

  @Override
  public String createServerTrid() {
    if (tm().reTransact(() -> FeatureFlag.isActiveNow(USE_RANDOM_SERVER_TRID))) {
      // The server TRID can be at most 64 characters. We generate 24 random bytes
      // (192 bits), which base64url-encodes without padding to 32 characters.
      // This provides an unpredictable TRID that does not leak pod identity or
      // command volume.
      byte[] randomBytes = new byte[24];
      secureRandom.get().nextBytes(randomBytes);
      return BaseEncoding.base64Url().omitPadding().encode(randomBytes);
    }
    // The server id can be at most 64 characters. The SERVER_ID is at most 22
    // characters (128
    // bits in base64), plus the dash. That leaves 41 characters, so we just append
    // the counter in
    // hex.
    return String.format("%s-%x", SERVER_ID, idCounter.incrementAndGet());
  }
}
