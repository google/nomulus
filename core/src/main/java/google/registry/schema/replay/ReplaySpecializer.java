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

package google.registry.schema.replay;

import google.registry.persistence.VKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Applies class-specific functions for model objects during transaction replays.
 *
 * <p>There are certain cases where changes to an entity require changes to other entities that are
 * not directly present in the other database. This class allows us to do that by using reflection
 * to invoke special class methods if they are present.
 */
public class ReplaySpecializer {

  public static void beforeSqlDelete(VKey<?> key) {
    invokeMethod(key.getKind(), "beforeSqlDelete", key);
  }

  public static void beforeSqlSave(SqlEntity sqlEntity) {
    invokeMethod(sqlEntity.getClass(), "beforeSqlSave", sqlEntity);
  }

  private static <T> void invokeMethod(Class<T> clazz, String methodName, Object argument) {
    try {
      Method method = clazz.getMethod(methodName, argument.getClass());
      method.invoke(null, argument);
    } catch (NoSuchMethodException e) {
      // Ignore, this just means that the class doesn't need this hook.
    } catch (IllegalAccessException e) {
      throw new RuntimeException(
          String.format(
              "%s() method is defined for class %s but is not public.",
              methodName, clazz.getName()),
          e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(
          String.format("%s() method for class %s threw an exception", methodName, clazz.getName()),
          e);
    }
  }
}
