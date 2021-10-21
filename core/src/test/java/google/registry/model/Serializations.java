// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/** Utilities for handling entity serialization. */
public final class Serializations {

  private Serializations() {}

  public static byte[] serialize(Serializable object) {
    try {
      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
          ObjectOutputStream oos = new ObjectOutputStream(bos)) {
        oos.writeObject(object);
        oos.flush();
        return bos.toByteArray();
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public static Serializable deserialize(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    try {
      try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
        return (Serializable) ois.readObject();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Serializable serializeDeserialize(Serializable object) {
    return deserialize(serialize(object));
  }
}
