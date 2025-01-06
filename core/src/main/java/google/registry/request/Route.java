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

package google.registry.request;

import java.util.function.Function;

/**
 * Mapping of an {@link Action} to a {@link Runnable} instantiator for request handling.
 *
 * @see Router
 */
record Route(Action action, Function<Object, Runnable> instantiator, Class<?> actionClass) {

  static Route create(
      Action action, Function<Object, Runnable> instantiator, Class<?> actionClass) {
    return new Route(action, instantiator, actionClass);
  }

  boolean isMethodAllowed(Action.Method requestMethod) {
    for (Action.Method method : action().method()) {
      if (method == requestMethod) {
        return true;
      }
    }
    return false;
  }
}
