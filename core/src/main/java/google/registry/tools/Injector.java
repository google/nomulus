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

package google.registry.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utilities for dependency injection using Dagger2.
 */
final class Injector {

  /**
   * Reflectively injects the dependencies of an object instance using Dagger2.
   *
   * <p>There must exist a method named {@code inject} on your {@code component} accepting a single
   * parameter whose type is identical to the class of {@code object}.
   *
   * <p><b>Note:</b> This is obviously slow since it uses reflection, which goes against the entire
   * philosophy of Dagger2. This method is only useful if you want to avoid avoid the boilerplate of
   * a super long chain of instanceof if statements, and you aren't concerned about performance.
   *
   * @return {@code true} if an appropriate injection method existed
   */
  static <T> boolean injectReflectively(Class<T> componentType, T component, Object object) {
    for (Method method : componentType.getMethods()) {
      if (!method.getName().equals("inject")) {
        continue;
      }
      Class<?> type = method.getParameterTypes()[0];
      if (type == object.getClass()) {
        try {
          method.invoke(component, object);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
          throw new RuntimeException(e);
        }
        return true;
      }
    }
    return false;
  }

  private Injector() {}
}
