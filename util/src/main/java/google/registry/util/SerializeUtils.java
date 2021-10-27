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

package google.registry.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.BaseEncoding.base16;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import javax.annotation.Nullable;

/** Utilities for easy serialization with informative error messages. */
public final class SerializeUtils {

  /**
   * Turns an object into a byte array.
   *
   * @return serialized object or {@code null} if {@code value} is {@code null}
   */
  @Nullable
  public static byte[] serialize(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    ByteArrayOutputStream objectBytes = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(objectBytes)) {
      oos.writeObject(value);
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to serialize: " + value, e);
    }
    return objectBytes.toByteArray();
  }

  /**
   * Turns a byte array into an object.
   *
   * @return deserialized object or {@code null} if {@code objectBytes} is {@code null}
   */
  @Nullable
  public static <T> T deserialize(Class<T> type, @Nullable byte[] objectBytes) {
    checkNotNull(type);
    if (objectBytes == null) {
      return null;
    }
    try {
      return type.cast(new ObjectInputStream(new ByteArrayInputStream(objectBytes)).readObject());
    } catch (ClassNotFoundException | IOException e) {
      throw new IllegalArgumentException(
          "Unable to deserialize: objectBytes=" + base16().encode(objectBytes), e);
    }
  }

  /** Serializes an object then deserializes it. This is typically used in tests. */
  public static Object serializeDeserialize(Object object) {
    return deserialize(Object.class, serialize(object));
  }

  private SerializeUtils() {}
}
