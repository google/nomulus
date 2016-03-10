// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package com.google.domain.registry.request;

import java.util.regex.Pattern;

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;

/**
 * Mapping of an {@link Action} to a {@link Runnable} instantiator for request handling.
 *
 * @see Router
 */
@AutoValue
public abstract class Route {

  static Route create(Action action, Function<Object, Runnable> instantiator) {
    return new AutoValue_Route(action, instantiator, compileActionPath(action.path()));
  }

  abstract Action action();
  abstract Function<Object, Runnable> instantiator();
  abstract Pattern pattern();

  boolean isMethodAllowed(Action.Method requestMethod) {
    for (Action.Method method : action().method()) {
      if (method == requestMethod) {
        return true;
      }
    }
    return false;
  }

  boolean shouldXsrfProtect(Action.Method requestMethod) {
    return action().xsrfProtection() && requestMethod != Action.Method.GET;
  }

  /**
   * Key used for router prefix matching.
   * Some routes are equipped with route parameters, which
   * would not allow the router to match the entire path
   * with the route. The routerKey() method will detect
   * route parameters and only return the path up to the
   * first parameter in the route. If there are no parameters,
   * the entire path of the action is returned.
   *
   * @return Key to be used in router prefix matching
   */
  String prefix() {
    String key = action().path();
    int i = key.indexOf("/:");
    if (i != -1) {
      key = key.substring(0, i + 1);
    }
    return key;
  }

  private static Pattern compileActionPath(String actionPath) {
    return Pattern.compile(""
        + "\\Q"
        + Pattern.compile(":([^/]+)")
            .matcher(actionPath)
            .replaceAll("\\\\E(?<$1>[^/]+)\\\\Q")
        + "\\E");
  }
}
