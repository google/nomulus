// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import google.registry.batch.CheckBulkComplianceAction;
import google.registry.flows.EppTlsAction;
import google.registry.request.Action;
import google.registry.testing.GoldenFileTestHelper;
import google.registry.testing.TestDataHelper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestComponent}. */
public class RequestComponentTest {

  /**
   * Production {@link Action} classes that are intentionally not (yet) wired into {@link
   * RequestComponent}, and are therefore unreachable at runtime.
   *
   * <p>Do not add new entries here without a tracking bug explaining why the action is unrouted.
   */
  private static final ImmutableSet<Class<?>> KNOWN_UNROUTED_ACTIONS =
      ImmutableSet.of(
          // TODO(b/261763205): Wire this into RequestComponent (and schedule it) once bulk pricing
          // packages launch, or delete it if bulk pricing is retired.
          CheckBulkComplianceAction.class);

  @Test
  void testRoutingMap() {
    GoldenFileTestHelper.assertThatRoutesFromComponent(RequestComponent.class)
        .describedAs("routing map")
        .isEqualToGolden(RequestComponentTest.class, "routing.txt");
  }

  @Test
  void testAllProductionActionsAreRouted() throws IOException {
    ImmutableSet<Class<?>> allProductionActions = getAllProductionActions();
    ImmutableSet<Class<?>> routedActions = getRoutedActions();

    // Sanity checks guarding against the classpath scan silently finding nothing.
    assertWithMessage("Production @Action classes discovered on the classpath")
        .that(allProductionActions)
        .containsAtLeast(CheckBulkComplianceAction.class, EppTlsAction.class);
    assertWithMessage("@Action classes routed by RequestComponent")
        .that(allProductionActions)
        .containsAtLeastElementsIn(routedActions);

    Set<Class<?>> unroutedActions = Sets.difference(allProductionActions, routedActions);
    assertWithMessage(
            "These production @Action classes are not wired into RequestComponent, so they are"
                + " unreachable. Register them in RequestComponent (and update routing.txt)")
        .that(Sets.difference(unroutedActions, KNOWN_UNROUTED_ACTIONS))
        .isEmpty();
    assertWithMessage(
            "These KNOWN_UNROUTED_ACTIONS entries are now routed or no longer exist. Remove them"
                + " from KNOWN_UNROUTED_ACTIONS")
        .that(Sets.difference(KNOWN_UNROUTED_ACTIONS, unroutedActions))
        .isEmpty();
  }

  /**
   * Returns all concrete {@link Action} classes compiled from the same (production) source set as
   * {@link RequestComponent}, which excludes {@link Action} classes defined in tests.
   */
  private static ImmutableSet<Class<?>> getAllProductionActions() throws IOException {
    CodeSource productionCodeSource = RequestComponent.class.getProtectionDomain().getCodeSource();
    return ClassPath.from(RequestComponent.class.getClassLoader()).getAllClasses().stream()
        .filter(classInfo -> classInfo.getName().startsWith("google.registry."))
        .map(ClassInfo::load)
        .filter(clazz -> clazz.isAnnotationPresent(Action.class))
        .filter(clazz -> !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()))
        .filter(clazz -> productionCodeSource.equals(clazz.getProtectionDomain().getCodeSource()))
        .collect(toImmutableSet());
  }

  /**
   * Returns the {@link Action} classes provided by {@link RequestComponent}, mirroring how {@code
   * Router} discovers routes from a component.
   */
  private static ImmutableSet<Class<?>> getRoutedActions() {
    return Arrays.stream(RequestComponent.class.getMethods())
        .filter(method -> method.getParameterCount() == 0)
        .map(Method::getReturnType)
        .filter(Runnable.class::isAssignableFrom)
        .filter(returnType -> returnType.isAnnotationPresent(Action.class))
        .collect(toImmutableSet());
  }

  private Set<Route> getRoutes(Class<?> context, String filename) {
    return TestDataHelper.loadFile(context, filename)
        .trim()
        .lines()
        .skip(1) // Skip the headers
        .map(Route::create)
        .collect(Collectors.toSet());
  }

  private record Route(
      String service,
      String path,
      String clazz,
      String methods,
      String ok,
      String min,
      String userPolicy) {
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings().trimResults();

    static Route create(String line) {
      ImmutableList<String> parts = ImmutableList.copyOf(splitter.split(line));
      assertThat(parts.size()).isEqualTo(7);
      return new Route(
          parts.get(0),
          parts.get(1),
          parts.get(2),
          parts.get(3),
          parts.get(4),
          parts.get(5),
          parts.get(6));
    }
  }
}
