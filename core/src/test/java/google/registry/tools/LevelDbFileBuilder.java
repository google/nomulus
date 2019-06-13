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

package google.registry.tools;

import static google.registry.tools.LevelDbLogReader.BLOCK_SIZE;
import static google.registry.tools.LevelDbLogReader.HEADER_SIZE;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.auto.value.AutoValue;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.tools.LevelDbLogReader.ChunkType;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Utility class for building a leveldb logfile. */
final class LevelDbFileBuilder {
  private static final String TEST_ENTITY_KIND = "TestEntity";

  private final FileOutputStream out;
  private byte[] currentBlock = new byte[BLOCK_SIZE];

  // Write position in the current block.
  private int currentPos = 0;

  LevelDbFileBuilder(File file) throws FileNotFoundException {
    out = new FileOutputStream(file);
  }

  /**
   * Adds a record containing a new entity protobuf to the file.
   *
   * <p>Returns the ComparableEntity object rather than "this" so that we can check for the presence
   * of the entity in the result set.
   */
  ComparableEntity addEntityProto(int id, Property... properties) throws IOException {
    Entity entity = new Entity(TEST_ENTITY_KIND, id);
    for (Property prop : properties) {
      entity.setProperty(prop.name(), prop.value());
    }
    EntityProto proto = EntityTranslator.convertToPb(entity);
    byte[] protoBytes = proto.toByteArray();
    if (protoBytes.length > BLOCK_SIZE - (currentPos + HEADER_SIZE)) {
      out.write(currentBlock);
      currentBlock = new byte[BLOCK_SIZE];
      currentPos = 0;
    }

    currentPos = LevelDbUtil.addRecord(currentBlock, currentPos, ChunkType.FULL, protoBytes);
    return new ComparableEntity(entity);
  }

  /** Writes all remaining data and closes the block. */
  void build() throws IOException {
    out.write(currentBlock);
    out.close();
  }

  @AutoValue
  abstract static class Property {
    static Property create(String name, Object value) {
      return new AutoValue_LevelDbFileBuilder_Property(name, value);
    }

    abstract String name();

    abstract Object value();
  }
}
