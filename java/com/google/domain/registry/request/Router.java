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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Path prefix request router for Domain Registry.
 *
 * <p>See the documentation of {@link RequestHandler} for more information.
 *
 * <h3>Implementation Details</h3>
 *
 * <p>Request routing is O(logn) because {@link ImmutableSortedMap} performs a binary search over a
 * contiguous array, which makes it faster than a {@link TreeMap}. However a prefix trie search in
 * generated code would be the ideal approach.
 */
public class Router {

  private final ImmutableSortedMap<String, ImmutableList<Route>> routes;

  public Router(Iterable<Method> methods) {
    this(extractRoutesFromComponent(methods));
  }

  private Router(ImmutableSortedMap<String, ImmutableList<Route>> routes) {
    this.routes = routes;
  }

  /** Returns the appropriate action route for a request. */
  Optional<Route> route(String path, Action.Method method) {
    checkNotNull(path, "Path cannot be null");
    checkNotNull(method, "Method cannot be null");

    Map.Entry<String, ImmutableList<Route>> floor = routes.floorEntry(path);
    if (floor != null) {
      // make a best effort to match both method and path.
      // if no methods match, return the first path match.
      // this allows the request handler to return a 405
      // Wrong Method response.
      Route firstPathMatch = null;

      for (Route route : floor.getValue()) {
        if (route.action().isPrefix()) {
          if (path.startsWith(route.action().path())) {
            return Optional.of(route);
          }
        } else {
          Matcher matcher = route.pattern().matcher(path);
          if (matcher.matches()) {
            if (route.isMethodAllowed(method)) {
              return Optional.of(route);
            } else if (firstPathMatch == null) {
              firstPathMatch = route;
            }
          }
        }
      }
      // if no routes were matched on both path and method,
      // but a route did match just based on path, return it
      return Optional.fromNullable(firstPathMatch);
    }
    return Optional.absent();
  }

  private static String normalizePath(String path) {
    return path.replaceAll(":[^/]+", "");
  }

  @AutoValue
  static abstract class RouteSignature {

    static RouteSignature create(String normalizedPath, Action.Method method) {
      return new AutoValue_Router_RouteSignature(normalizedPath, method);
    }

    abstract String normalizedPath();
    abstract Action.Method method();
  }

  /**
   * Validates the new route. Checks that the route doesn't start with
   * a parameter and ensures that the new route being added to the collection
   * is not a duplicate of a previous route. Multiple routes cannot have
   * the same path and overlap in allowed http methods.
   */
  private static void validateRoute(Route newRoute, Iterable<Route> existingRoutes) {
    checkArgument(newRoute.action().path().indexOf("/:") != 0,
        "Route cannot begin with a route parameter");
    Set<RouteSignature> routeSignatures = new HashSet<>();
    for (Route existingRoute : existingRoutes) {
      String existingRoutePath = normalizePath(existingRoute.action().path());
      for (Action.Method m : existingRoute.action().method()) {
        RouteSignature signature = RouteSignature.create(existingRoutePath, m);
        routeSignatures.add(signature);
      }
    }
    String newRoutePath = normalizePath(newRoute.action().path());
    for (Action.Method m : newRoute.action().method()) {
      RouteSignature signature = RouteSignature.create(newRoutePath, m);
      checkArgument(routeSignatures.add(signature),
          "Two actions cannot have the same path and method");
    }

  }

  private static ImmutableSortedMap<String, ImmutableList<Route>> extractRoutesFromComponent(
      Iterable<Method> methods) {
    // Values may need to be replaced for keys during the build,
    // so a mutable HashMap is used to build the mapping of routes
    // before copying the result to an ImmutableSortedMap.

    // TODO: investigate the possibility of using TreeMultimap instead
    // with Multimaps.unmodifiableMultimap(..) at the end.
    Map<String, ImmutableList<Route>> routes = new HashMap<>();
    for (Method method : methods) {
      if (!isDaggerInstantiatorOfType(Runnable.class, method)) {
        continue;
      }
      Action action = method.getReturnType().getAnnotation(Action.class);
      if (action == null) {
        continue;
      }
      @SuppressWarnings("unchecked")  // Safe due to previous checks.
      Route route =
          Route.create(action, (Function<Object, Runnable>) newInstantiator(method));
      // some collections of routes need to be grouped under the same key,
      // then matched based on regular expression to allow for route parameters.
      // see RouteCollection.java and RouteMatcher.java
      ImmutableList<Route> routeCollection = routes.get(route.prefix());
      if (routeCollection == null) {
        validateRoute(route, ImmutableList.<Route>of());
        routeCollection = ImmutableList.of(route);
      } else {
        validateRoute(route, routeCollection);
        routeCollection = new ImmutableList.Builder<Route>()
            .addAll(routeCollection)
            .add(route)
            .build();
      }
      routes.put(route.prefix(), routeCollection);
    }
    return ImmutableSortedMap.copyOf(routes, Ordering.natural());
  }

  private static boolean isDaggerInstantiatorOfType(Class<?> type, Method method) {
    return method.getParameterTypes().length == 0
        && type.isAssignableFrom(method.getReturnType());
  }

  private static Function<Object, ?> newInstantiator(final Method method) {
    return new Function<Object, Object>() {
      @Override
      public Object apply(Object component) {
        try {
          return method.invoke(component);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(
              "Error reflectively accessing component's @Action factory method", e);
        } catch (InvocationTargetException e) {
          // This means an exception was thrown during the injection process while instantiating
          // the @Action class; we should propagate that underlying exception.
          Throwables.propagateIfPossible(e.getCause());
          throw new AssertionError(
              "Component's @Action factory method somehow threw checked exception", e);
        }
      }};
  }
}
