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

package google.registry.model;

import static com.google.common.base.Predicates.or;
import static com.google.common.base.Predicates.subtypeOf;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.Ordering;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.SortedSet;
import java.util.TreeSet;

/** Utility methods for getting the version of the model schema from the model code. */
@DeleteAfterMigration
public final class SchemaVersion {

  /**
   * Returns a set of classes corresponding to all types persisted within the model classes, sorted
   * by the string representation.
   */
  private static SortedSet<Class<?>> getAllPersistedTypes() {
    SortedSet<Class<?>> persistedTypes = new TreeSet<>(Ordering.usingToString());
    // Do a breadth-first search for persisted types, starting with @Entity types and expanding each
    // ImmutableObject by querying it for all its persisted field types.
    persistedTypes.addAll(EntityClasses.ALL_CLASSES);
    Queue<Class<?>> queue = new ArrayDeque<>(persistedTypes);
    while (!queue.isEmpty()) {
      Class<?> clazz = queue.remove();
      if (ImmutableObject.class.isAssignableFrom(clazz)) {
        for (Class<?> persistedFieldType : ModelUtils.getPersistedFieldTypes(clazz)) {
          if (persistedTypes.add(persistedFieldType)) {
            // If we haven't seen this type before, add it to the queue to query its field types.
            queue.add(persistedFieldType);
          }
        }
      }
    }
    return persistedTypes;
  }

  /**
   * Return a string representing the schema which includes the definition of all persisted entity
   * types (and their field types, recursively). Each definition contains the field names and their
   * types (for classes), or else a list of all possible values (for enums).
   */
  public static String getSchema() {
    return getAllPersistedTypes()
        .stream()
        .filter(or(subtypeOf(Enum.class), subtypeOf(ImmutableObject.class)))
        .map(ModelUtils::getSchema)
        .collect(joining("\n"));
  }

  private SchemaVersion() {}
}
