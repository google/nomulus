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

package google.registry.request;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Path prefix request router.
 *
 * <p>See the documentation of {@link RequestHandler} for more information.
 *
 * <h3>Implementation Details</h3>
 *
 * <p>Request routing is O(logn) because {@link ImmutableSortedMap} performs a binary search over a
 * contiguous array, which makes it faster than a {@link TreeMap}. However a prefix trie search in
 * generated code would be the ideal approach.
 */
final class Router {

  /** Create a new Router for the given component class. */
  static Router create(Class<?> componentClass) {
    return new Router(componentClass);
  }

  private final ImmutableSortedMap<String, Route> routes;

  private Router(Class<?> componentClass) {
    this.routes = extractRoutesFromComponent(componentClass);
    checkArgument(
        !this.routes.isEmpty(), "No routes found for class: %s", componentClass.getCanonicalName());
  }

  /** Returns the appropriate action route for a request. */
  Optional<Route> route(String path) {
    Map.Entry<String, Route> floor = routes.floorEntry(path);
    if (floor != null) {
      String key = floor.getKey();
      Route value = floor.getValue();
      if ((value.action().isPrefix() && path.startsWith(key))
          || path.equals(key)
          || path.equals(key + '/')) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  static ImmutableSortedMap<String, Route> extractRoutesFromComponent(Class<?> componentClass) {
    ImmutableSortedMap.Builder<String, Route> routes =
        new ImmutableSortedMap.Builder<>(Ordering.natural());
    for (Method method : componentClass.getMethods()) {
      // Make App Engine's security manager happy.
      method.setAccessible(true);
      if (!isDaggerInstantiatorOfType(Runnable.class, method)) {
        continue;
      }
      Action action = method.getReturnType().getAnnotation(Action.class);
      if (action == null) {
        continue;
      }
      @SuppressWarnings("unchecked") // Safe due to previous checks.
      Route route =
          Route.create(
              action,
              (Function<Object, Runnable>) newInstantiator(method),
              method.getReturnType());
      routes.put(action.path(), route);
    }
    return routes.build();
  }

  private static boolean isDaggerInstantiatorOfType(Class<?> type, Method method) {
    return method.getParameterTypes().length == 0
        && type.isAssignableFrom(method.getReturnType());
  }

  private static Function<Object, ?> newInstantiator(final Method method) {
    return component -> {
      try {
        return method.invoke(component);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(
            "Error reflectively accessing component's @Action factory method", e);
      } catch (InvocationTargetException e) {
        // This means an exception was thrown during the injection process while instantiating
        // the @Action class; we should propagate that underlying exception.
        throwIfUnchecked(e.getCause());
        throw new AssertionError(
            "Component's @Action factory method somehow threw checked exception", e);
      }
    };
  }
}
