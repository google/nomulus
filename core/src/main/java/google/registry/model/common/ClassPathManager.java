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

package google.registry.model.common;

import static com.google.common.base.Functions.identity;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.EntityClasses.ALL_CLASSES;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.annotation.EntitySubclass;
import java.util.Map;
import java.util.stream.Collectors;

/** A helper to manage class name and class path mapping. */
public class ClassPathManager {
  /**
   * Class registry allowing us to restore the original class object from the unqualified class
   * name, which is all the datastore key gives us. Note that entities annotated
   * with @EntitySubclass are removed because they share the same kind of the key with their parent
   * class.
   */
  public static final Map<String, Class<?>> CLASS_REGISTRY =
      ALL_CLASSES.stream()
          .filter(clazz -> !clazz.isAnnotationPresent(EntitySubclass.class))
          .collect(Collectors.toMap(com.googlecode.objectify.Key::getKind, identity()));

  /**
   * Class name registry allowing us to obtain the class name the unqualified class, which is all
   * the datastore key gives us. Note that entities annotated with @EntitySubclass are removed
   * because they share the same kind of the key with their parent class.
   */
  public static final Map<Class<?>, String> CLASS_NAME_REGISTRY =
      ALL_CLASSES.stream()
          .filter(clazz -> !clazz.isAnnotationPresent(EntitySubclass.class))
          .collect(Collectors.toMap(identity(), com.googlecode.objectify.Key::getKind));

  @VisibleForTesting
  public static void addTestEntityClass(Class<?> clazz) {
    CLASS_REGISTRY.put(com.googlecode.objectify.Key.getKind(clazz), clazz);
  }

  public static <T> Class<T> getClass(String className) {
    checkArgument(CLASS_REGISTRY.containsKey(className), "Class not found in class registry");
    return (Class<T>) CLASS_REGISTRY.get(className);
  }

  public static <T> String getClassName(Class<T> clazz) {
    checkArgument(CLASS_NAME_REGISTRY.containsKey(clazz), "Class not found in class name registry");
    return CLASS_NAME_REGISTRY.get(clazz);
  }
}
