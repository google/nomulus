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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.model.EppResource;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import io.protostuff.Input;
import io.protostuff.LinkedBuffer;
import io.protostuff.Output;
import io.protostuff.Pipe;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.WireFormat;
import io.protostuff.runtime.DefaultIdStrategy;
import io.protostuff.runtime.Delegate;
import io.protostuff.runtime.RuntimeSchema;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
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
public class SimplifiedJedisClient {

  public record JedisResource<V extends EppResource>(String key, V value) {}

  private static final ImmutableMap<Class<? extends EppResource>, String> TYPE_PREFIXES =
      ImmutableMap.of(
          Domain.class, "d_",
          Host.class, "h_");

  /** We need to inform Protostuff of the custom {@link InetAddress} delegates. */
  private static DefaultIdStrategy createIdStrategy() {
    DefaultIdStrategy strategy = new DefaultIdStrategy();
    strategy.registerDelegate(new GenericInetAddressDelegate<>(InetAddress.class));
    strategy.registerDelegate(new GenericInetAddressDelegate<>(Inet4Address.class));
    strategy.registerDelegate(new GenericInetAddressDelegate<>(Inet6Address.class));
    return strategy;
  }

  private static final ImmutableMap<Class<? extends EppResource>, Schema<? extends EppResource>>
      VALUE_SCHEMAS =
          ImmutableMap.of(
              Domain.class, RuntimeSchema.getSchema(Domain.class),
              Host.class, RuntimeSchema.getSchema(Host.class, createIdStrategy()));

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int BATCH_SIZE = 500;

  private final UnifiedJedis jedis;

  SimplifiedJedisClient(UnifiedJedis jedis) {
    this.jedis = jedis;
  }

  /** Gets the value from the remote cache. Returns null if it does not exist. */
  public <V extends EppResource> Optional<V> get(Class<V> clazz, String key) {
    checkNotNull(key, "Key cannot be null");
    byte[] data = jedis.get(convertKey(clazz, key));
    return Optional.ofNullable(data).map(d -> deserialize(clazz, d));
  }

  /** Sets the value in the remote cache. */
  public <V extends EppResource> void set(JedisResource<V> resource) {
    checkNotNull(resource.key, "Key cannot be null");
    checkNotNull(resource.value, "Value cannot be null");
    jedis.set(
        convertKey(resource.value.getClass(), resource.key),
        serialize(resource.value),
        new SetParams().pxAt(resource.value.getDeletionTime().toEpochMilli()));
  }

  /** Sets multiple values in the remote cache using a Jedis {@link AbstractPipeline}. */
  public <V extends EppResource> void setAll(ImmutableCollection<JedisResource<V>> resources) {
    logger.atInfo().log("Processing %d resources", resources.size());
    for (Iterable<JedisResource<V>> batch : Iterables.partition(resources, BATCH_SIZE)) {
      try (AbstractPipeline pipeline = jedis.pipelined()) {
        batch.forEach(
            resource ->
                pipeline.set(
                    convertKey(resource.value.getClass(), resource.key),
                    serialize(resource.value),
                    new SetParams().pxAt(resource.value.getDeletionTime().toEpochMilli())));
        pipeline.sync();
      }
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
  public void deleteAll(Class<?> valueType, ImmutableCollection<String> keys) {
    // we use a reasonably small batch size to avoid overwhelming the network
    for (Iterable<String> batch : Iterables.partition(keys, BATCH_SIZE)) {
      byte[][] keysToUnlink =
          Streams.stream(batch).map(key -> convertKey(valueType, key)).toArray(byte[][]::new);
      jedis.unlink(keysToUnlink);
    }
  }

  private <V extends EppResource> byte[] serialize(V value) {
    @SuppressWarnings("unchecked")
    Schema<V> valueSchema = (Schema<V>) getValueSchema(value.getClass());
    LinkedBuffer buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
    try {
      return ProtostuffIOUtil.toByteArray(value, valueSchema, buffer);
    } finally {
      buffer.clear();
    }
  }

  private <V extends EppResource> V deserialize(Class<V> clazz, byte[] data) {
    // We use protobufs because other deserializers don't play nicely with immutable collections
    Schema<V> valueSchema = getValueSchema(clazz);
    V value = valueSchema.newMessage();
    ProtostuffIOUtil.mergeFrom(data, value, valueSchema);
    return value;
  }

  private byte[] convertKey(Class<?> clazz, String key) {
    checkArgument(TYPE_PREFIXES.containsKey(clazz), "Unknown class type %s", clazz);
    return (TYPE_PREFIXES.get(clazz) + key).getBytes(StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private <V extends EppResource> Schema<V> getValueSchema(Class<V> clazz) {
    checkArgument(VALUE_SCHEMAS.containsKey(clazz), "Unknown class type %s", clazz);
    return (Schema<V>) VALUE_SCHEMAS.get(clazz);
  }

  /**
   * A custom Protostuff {@link Delegate} for {@link InetAddress} and its subclasses.
   *
   * <p>This is required in Java 17+ because Protostuff's default runtime schema serialization
   * relies on reflection. Since {@link InetAddress} is part of the encapsulated {@code java.base}
   * module, reflective access is restricted and throws {@link
   * java.lang.reflect.InaccessibleObjectException}.
   *
   * <p>This delegate serializes the IP address as a raw byte array using {@link
   * InetAddress#getAddress()} and reconstructs it using {@link InetAddress#getByAddress(byte[])}
   */
  private record GenericInetAddressDelegate<T extends InetAddress>(Class<T> clazz)
      implements Delegate<T> {

    @Override
    public WireFormat.FieldType getFieldType() {
      return WireFormat.FieldType.BYTES;
    }

    @Override
    public Class<T> typeClass() {
      return clazz;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T readFrom(Input input) throws IOException {
      return (T) InetAddress.getByAddress(input.readByteArray());
    }

    @Override
    public void writeTo(Output output, int number, T value, boolean repeated) throws IOException {
      output.writeByteArray(number, value.getAddress(), repeated);
    }

    @Override
    public void transfer(Pipe pipe, Input input, Output output, int number, boolean repeated)
        throws IOException {
      output.writeByteArray(number, input.readByteArray(), repeated);
    }
  }
}
