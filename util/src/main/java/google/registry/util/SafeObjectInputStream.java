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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.Collection;
import java.util.Map;

/**
 * Safely deserializes Nomulus http request parameters.
 *
 * <p>Serialized Java objects may be passed between Nomulus components that hold different
 * credentials. Deserialization of such objects should be protected against attacks through
 * compromised accounts.
 *
 * <p>This class protects against three types of attacks by restricting the classes used for
 * serialization:
 *
 * <ul>
 *   <li>Remote code execution by referencing bad classes in compromised jars. When a class with
 *       malicious code in the static initialization block or the deserialization code path (e.g.,
 *       the {@code readObject} method) is deserialized, such code will be executed. For Nomulus,
 *       this risk comes from third-party dependencies. To counter this risk, this class only allows
 *       Nomulus (google.registry.**) classes and specific core Java classes, and forbid others
 *       including third-party dependencies. (As a side note, this class does not use allow lists
 *       for Nomulus or third-party classes because it is infeasible in practice. Super classes of
 *       the instance being deserialized must be resolved, and therefore must be on the allow list;
 *       same for the field types of the instance. The allow list for the Joda {@code DateTime}
 *       class alone would have more than 10 classes. Generated classes, e.g., by AutoValue, present
 *       another problem: their real names are not meant to be a concern to the user).
 *   <li>CPU-targeting denial-of-service attacks. Containers and arrays may be used to construct
 *       object graphs that require enormous amount of computation during deserialization and/or
 *       during invocations of methods such as {@code hashCode} or {@code equals}, taking minutes or
 *       even hours to complete. See <a
 *       href="https://owasp.org/www-community/vulnerabilities/Deserialization_of_untrusted_data">
 *       here</a> for an example of such object graphs. To counter this risk, this class forbids
 *       lists, maps, and arrays for deserialization.
 *   <li>Memory-targeting denial-of-service attacks. By forbidding container and arrays, this class
 *       also prevents some memory-targeting attacks, e.g., using wire format that claims to be an
 *       array of a huge size, causing the JVM to preallocate excessive amount of memory and
 *       triggering the {@code OutOfMemoryError}. This is actually a small risk for Nomulus, since
 *       the impact of each error is limited to a single (spurious) request.
 * </ul>
 *
 * <p>Nomulus classes with fields of array, container, or third-party Java types must implement
 * their own serialization/deserialization methods to be safely deserialized. For the common use
 * case of passing a collection of `safe` objects, {@link
 * SafeSerializationUtils#serializeCollection} and {@link
 * SafeSerializationUtils#safeDeserializeCollection} may be used.
 */
public final class SafeObjectInputStream extends ObjectInputStream {

  /**
   * Core Java classes allowed in deserialization. Add new classes as needed but do not add
   * third-party classes.
   */
  private static final ImmutableSet<String> ALLOWED_CORE_JAVA_CLASSES =
      ImmutableSet.of(String.class, Byte.class, Short.class, Integer.class, Long.class).stream()
          .map(Class::getName)
          .collect(toImmutableSet());

  public SafeObjectInputStream(InputStream in) throws IOException {
    super(in);
  }

  @Override
  protected Class<?> resolveClass(ObjectStreamClass desc)
      throws ClassNotFoundException, IOException {
    String clazz = desc.getName();
    if (isNomulusClass(clazz) || ALLOWED_CORE_JAVA_CLASSES.contains(clazz)) {
      return checkNotArrayOrContainer(super.resolveClass(desc));
    }
    throw new ClassNotFoundException(clazz + " not found or not allowed in deserialization.");
  }

  private Class<?> checkNotArrayOrContainer(Class<?> clazz) throws ClassNotFoundException {
    if (isContainer(clazz) || clazz.isArray()) {
      throw new ClassNotFoundException(clazz.getName() + " not allowed as non-root object.");
    }
    return clazz;
  }

  private boolean isNomulusClass(String clazz) {
    return clazz.startsWith("google.registry.");
  }

  private boolean isContainer(Class<?> clazz) {
    return Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz);
  }
}
