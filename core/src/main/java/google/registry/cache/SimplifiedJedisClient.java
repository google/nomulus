// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.cache;

import static com.google.common.base.Preconditions.checkNotNull;

import google.registry.model.EppResource;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import redis.clients.jedis.UnifiedJedis;

/**
 * A {@link UnifiedJedis} client that handles serialization/deserialization.
 *
 * <p>We use protobufs for serialization to handle the immutable collections that our objects use.
 *
 * <p>{@link UnifiedJedis} pairs key-value types, so we need the key to be serialized to a byte
 * array as well.
 */
public class SimplifiedJedisClient<V extends EppResource> {

  private final Schema<V> valueSchema;
  private final UnifiedJedis jedis;

  public static <V extends EppResource> SimplifiedJedisClient<V> create(
      Class<V> valueClass, UnifiedJedis jedis) {
    Schema<V> valueSchema = RuntimeSchema.getSchema(valueClass);
    return new SimplifiedJedisClient<>(valueSchema, jedis);
  }

  private SimplifiedJedisClient(Schema<V> valueSchema, UnifiedJedis jedis) {
    this.valueSchema = valueSchema;
    this.jedis = jedis;
  }

  /** Gets the value from the remote cache. Returns null if it does not exist. */
  public Optional<V> get(String key) {
    checkNotNull(key, "Key cannot be null");
    byte[] data = jedis.get(key.getBytes(StandardCharsets.UTF_8));
    return Optional.ofNullable(data).map(this::deserialize);
  }

  /** Sets the value in the remote cache. */
  public void set(String key, V value) {
    checkNotNull(key, "Key cannot be null");
    checkNotNull(value, "Value cannot be null");
    jedis.set(key.getBytes(StandardCharsets.UTF_8), serialize(value));
  }

  private byte[] serialize(V value) {
    LinkedBuffer buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
    try {
      return ProtostuffIOUtil.toByteArray(value, valueSchema, buffer);
    } finally {
      buffer.clear();
    }
  }

  private V deserialize(byte[] data) {
    V value = valueSchema.newMessage();
    ProtostuffIOUtil.mergeFrom(data, value, valueSchema);
    return value;
  }
}
