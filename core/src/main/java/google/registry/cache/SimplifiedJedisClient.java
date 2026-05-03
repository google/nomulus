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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import google.registry.model.EppResource;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * A {@link UnifiedJedis} client that handles serialization/deserialization.
 *
 * <p>We use protobufs for serialization to handle the immutable collections that our objects use.
 *
 * <p>{@link UnifiedJedis} pairs key-value types, so we need the key to be serialized to a byte
 * array as well.
 */
public class SimplifiedJedisClient<V extends EppResource> {

  public record JedisResource<V extends EppResource>(String key, V value) {}

  private static final int BATCH_SIZE = 500;

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
  public void set(JedisResource<V> resource) {
    checkNotNull(resource.key, "Key cannot be null");
    checkNotNull(resource.value, "Value cannot be null");
    jedis.set(
        resource.key.getBytes(StandardCharsets.UTF_8),
        serialize(resource.value),
        new SetParams().pxAt(resource.value.getDeletionTime().toEpochMilli()));
  }

  /** Sets multiple values in the remote cache using a Jedis {@link AbstractPipeline}. */
  public void setAll(ImmutableCollection<JedisResource<V>> resources) {
    for (Iterable<JedisResource<V>> batch : Iterables.partition(resources, BATCH_SIZE)) {
      AbstractPipeline pipeline = jedis.pipelined();
      batch.forEach(
          resource ->
              pipeline.set(
                  resource.key.getBytes(StandardCharsets.UTF_8),
                  serialize(resource.value),
                  new SetParams().pxAt(resource.value.getDeletionTime().toEpochMilli())));
      pipeline.sync();
    }
  }

  /**
   * Deletes all values associated with the given keys in Valkey.
   *
   * <p>If any given key does not exist, it does nothing.
   *
   * <p>Note: we use {@code unlink} here instead of {@code del} so that the actual deletion can
   * happen in the background whenever the server wants. The keys are removed from the namespace
   * immediately, and we don't need the memory to be reclaimed this instant.
   *
   * <p>This could also be accomplished by using {@link #setAll(ImmutableCollection)} with
   * expiration times that are in the past, but this is clearer.
   */
  public void deleteAll(ImmutableCollection<String> keys) {
    // we use a reasonably small batch size to avoid overwhelming the network
    for (Iterable<String> batch : Iterables.partition(keys, BATCH_SIZE)) {
      byte[][] keysToUnlink =
          Streams.stream(batch)
              .map(key -> key.getBytes(StandardCharsets.UTF_8))
              .toArray(byte[][]::new);
      jedis.unlink(keysToUnlink);
    }
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
    // We use protobufs because other deserializers don't play nicely with immutable collections
    V value = valueSchema.newMessage();
    ProtostuffIOUtil.mergeFrom(data, value, valueSchema);
    return value;
  }
}
