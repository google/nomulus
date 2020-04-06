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

import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableSet;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.Predicate;

/** Accumulates Entity records from level db files under a directory hierarchy. */
class RecordAccumulator {
  private final LevelDbLogReader reader = new LevelDbLogReader();

  /** Recursively reads all records in the directory. */
  public final RecordAccumulator readDirectory(File dir, Predicate<File> fileMatcher) {
    for (File child : dir.listFiles()) {
      if (child.isDirectory()) {
        readDirectory(child, fileMatcher);
      } else if (fileMatcher.test(child)) {
        try {
          reader.readFrom(new FileInputStream(child));
        } catch (IOException e) {
          throw new RuntimeException("IOException reading from file: " + child, e);
        }
      }
    }

    return this;
  }

  /** Creates an entity set from the current set of raw records. */
  ImmutableSet<ComparableEntity> getComparableEntitySet() {
    ImmutableSet.Builder<ComparableEntity> builder = new ImmutableSet.Builder<>();
    for (byte[] rawRecord : reader.getRecords()) {
      // Parse the entity proto and create an Entity object from it.
      EntityProto proto = new EntityProto();
      proto.parseFrom(rawRecord);
      ComparableEntity entity = new ComparableEntity(EntityTranslator.createFromPb(proto));

      builder.add(entity);
    }

    return builder.build();
  }
}
