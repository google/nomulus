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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A server Trid provider that generates globally incrementing UUIDs. */
public class ServerTridProviderImpl implements ServerTridProvider {

  private static final String SERVER_ID = getServerId();
  private static final AtomicLong idCounter = new AtomicLong();

  @Inject public ServerTridProviderImpl() {}

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
    // The server id can be at most 64 characters. The SERVER_ID is at most 22 characters (128
    // bits in base64), plus the dash. That leaves 41 characters, so we just append the counter in
    // hex.
    return String.format("%s-%x", SERVER_ID, idCounter.incrementAndGet());
  }
}
