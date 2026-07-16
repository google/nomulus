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

import static com.google.common.primitives.Longs.BYTES;

import com.google.common.io.BaseEncoding;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/** A server Trid provider that generates secure random UUID-based transaction IDs. */
public class ServerTridProviderImpl implements ServerTridProvider {

  private static final String SERVER_ID = getServerId();
  
  private final SecureRandom secureRandom;

  @Inject
  public ServerTridProviderImpl(SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
  }

  /** Creates a unique id for this server instance, as a base64 encoded UUID. */
  private static String getServerId() {
    UUID uuid = UUID.randomUUID();
    ByteBuffer buffer =
        ByteBuffer.allocate(BYTES * 2)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits());
    return BaseEncoding.base64().encode(buffer.array());
  }

  @Override
  public String createServerTrid() {
    // The server id can be at most 64 characters. The SERVER_ID is at most 24 characters (128
    // bits in base64), plus the dash. That leaves 39 characters, so we generate a random 15-byte
    // (120-bit) suffix, base64url-encoded without padding (20 characters).
    byte[] randomBytes = new byte[15];
    secureRandom.nextBytes(randomBytes);
    String randomSuffix = BaseEncoding.base64Url().omitPadding().encode(randomBytes);
    return String.format("%s-%s", SERVER_ID, randomSuffix);
  }
}
