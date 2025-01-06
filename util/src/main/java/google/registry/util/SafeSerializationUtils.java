// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * Helpers for using {@link SafeObjectInputStream}.
 *
 * <p>Please refer to {@code SafeObjectInputStream} for more information.
 */
public final class SafeSerializationUtils {

  private SafeSerializationUtils() {}

  /**
   * Maximum number of elements allowed in a serialized collection.
   *
   * <p>This value is sufficient for parameters embedded in a {@code URL} to typical cloud services.
   * E.g., as of Fall 2023, AWS limits request line size to 16KB and GCP limits total header size to
   * 64KB.
   */
  public static final int MAX_COLLECTION_SIZE = 32768;

  /**
   * Serializes a collection of objects that can be safely deserialized using {@link
   * #safeDeserializeCollection}.
   *
   * <p>If any element of the collection cannot be safely-deserialized, deserialization will fail.
   */
  public static byte[] serializeCollection(Collection<?> collection) {
    checkNotNull(collection, "collection");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ObjectOutputStream os = new ObjectOutputStream(bos)) {
      os.writeInt(collection.size());
      for (Object obj : collection) {
        os.writeObject(obj);
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize: " + collection, e);
    }
    return bos.toByteArray();
  }

  /** Safely deserializes an object using {@link SafeObjectInputStream}. */
  @Nullable
  public static Serializable safeDeserialize(@Nullable byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    try (ObjectInputStream is = new SafeObjectInputStream(new ByteArrayInputStream(bytes))) {
      return (Serializable) is.readObject();
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalArgumentException("Failed to deserialize: " + Arrays.toString(bytes), e);
    }
  }

  /**
   * Safely deserializes a collection of objects previously serialized with {@link
   * #serializeCollection}.
   */
  public static <T> ImmutableList<T> safeDeserializeCollection(Class<T> elementType, byte[] bytes) {
    checkNotNull(bytes, "Serialized list must not be null.");
    try (ObjectInputStream is = new SafeObjectInputStream(new ByteArrayInputStream(bytes))) {
      int size = is.readInt();
      checkArgument(size >= 0, "Malformed data: negative collection size.");
      if (size > MAX_COLLECTION_SIZE) {
        throw new IllegalArgumentException("Too many elements in collection: " + size);
      }
      ImmutableList.Builder<T> builder = new ImmutableList.Builder<>();
      for (int i = 0; i < size; i++) {
        builder.add(elementType.cast(is.readObject()));
      }
      return builder.build();
    } catch (IOException | ClassNotFoundException | ClassCastException e) {
      throw new IllegalArgumentException("Failed to deserialize: " + Arrays.toString(bytes), e);
    }
  }
}
