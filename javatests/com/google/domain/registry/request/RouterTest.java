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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.domain.registry.request.Action.Method;
import com.google.domain.registry.testing.ExceptionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;

/** Unit tests for {@link Router}. */
@RunWith(JUnit4.class)
public final class RouterTest {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  private Router create(Class<?> clazz) {
    return new Router(ImmutableList.copyOf(clazz.getMethods()));
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  public interface Empty {}

  @Test
  public void testRoute_noRoutes_returnsAbsent() throws Exception {
    assertThat(create(Empty.class).route("", Method.GET)).isAbsent();
    assertThat(create(Empty.class).route("/", Method.GET)).isAbsent();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/sloth")
  public final class SlothTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface SlothComponent {
    SlothTask slothTask();
  }

  @Test
  public void testRoute_pathMatch_returnsRoute() throws Exception {
    Optional<Route> route = create(SlothComponent.class).route("/sloth", Method.GET);
    assertThat(route).isPresent();
    assertThat(route.get().action().path()).isEqualTo("/sloth");
    assertThat(route.get().instantiator()).isInstanceOf(Function.class);
  }

  @Test
  public void testRoute_pathMismatch_returnsAbsent() throws Exception {
    assertThat(create(SlothComponent.class).route("/doge", Method.GET)).isAbsent();
  }

  @Test
  public void testRoute_pathIsAPrefix_notAllowedByDefault() throws Exception {
    assertThat(create(SlothComponent.class).route("/sloth/extra", Method.GET)).isAbsent();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/prefix", isPrefix = true)
  public final class PrefixTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface PrefixComponent {
    PrefixTask prefixTask();
  }

  @Test
  public void testRoute_prefixMatches_returnsRoute() throws Exception {
    assertThat(create(PrefixComponent.class).route("/prefix", Method.GET)).isPresent();
    assertThat(create(PrefixComponent.class).route("/prefix/extra", Method.GET)).isPresent();
  }

  @Test
  public void testRoute_prefixDoesNotMatch_returnsAbsent() throws Exception {
    assertThat(create(PrefixComponent.class).route("", Method.GET)).isAbsent();
    assertThat(create(PrefixComponent.class).route("/", Method.GET)).isAbsent();
    assertThat(create(PrefixComponent.class).route("/ulysses", Method.GET)).isAbsent();
    assertThat(create(PrefixComponent.class).route("/man/of/sadness", Method.GET)).isAbsent();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/prefix/long", isPrefix = true)
  public final class LongTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface LongPathComponent {
    PrefixTask prefixTask();
    LongTask longTask();
  }

  @Test
  public void testRoute_prefixAndLongPathMatch_returnsLongerPath() throws Exception {
    Optional<Route> route = create(LongPathComponent.class).route("/prefix/long", Method.GET);
    assertThat(route).isPresent();
    assertThat(route.get().action().path()).isEqualTo("/prefix/long");
  }

  @Test
  public void testRoute_prefixAndLongerPrefixMatch_returnsLongerPrefix() throws Exception {
    Optional<Route> route = create(LongPathComponent.class).route("/prefix/longer", Method.GET);
    assertThat(route).isPresent();
    assertThat(route.get().action().path()).isEqualTo("/prefix/long");
  }

  @Test
  public void testRoute_onlyShortPrefixMatches_returnsShortPrefix() throws Exception {
    Optional<Route> route = create(LongPathComponent.class).route("/prefix/cat", Method.GET);
    assertThat(route).isPresent();
    assertThat(route.get().action().path()).isEqualTo("/prefix");
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  public interface WeirdMethodsComponent {
    SlothTask hasAnArgumentWhichIsIgnored(boolean lol);
    Callable<?> notARunnableWhichIsIgnored();
  }

  @Test
  public void testRoute_methodsInComponentAreIgnored_noRoutes() throws Exception {
    assertThat(create(WeirdMethodsComponent.class).route("/sloth", Method.GET)).isAbsent();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/samePathAsOtherTask")
  public final class DuplicateTask1 implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/samePathAsOtherTask")
  public final class DuplicateTask2 implements Runnable {
    @Override
    public void run() {}
  }

  public interface DuplicateComponent {
    DuplicateTask1 duplicateTask1();
    DuplicateTask2 duplicateTask2();
  }

  @Test
  public void testCreate_twoTasksWithSameMethodAndPath_resultsInError() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "Two actions cannot have the same path and method");
    create(DuplicateComponent.class);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/samePathAsOtherTask", method={Method.GET})
  public final class DuplicatePathGetTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/samePathAsOtherTask", method={Method.POST})
  public final class DuplicatePathPostTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface DuplicatePathsDifferentMethodsComponent {
    DuplicatePathGetTask getTask();
    DuplicatePathPostTask postTask();
  }

  @Test
  public void testCreate_twoTasksWithSamePathDifferentMethods_routesByMethod() throws Exception {
    Router router = create(DuplicatePathsDifferentMethodsComponent.class);

    assertThat(router.route("/samePathAsOtherTask", Method.GET)).isPresent();
    assertThat(router.route("/samePathAsOtherTask", Method.GET).get()
        .isMethodAllowed(Method.GET)).isTrue();
    assertThat(router.route("/samePathAsOtherTask", Method.GET).get()
        .isMethodAllowed(Method.POST)).isFalse();

    assertThat(router.route("/samePathAsOtherTask", Method.POST)).isPresent();
    assertThat(router.route("/samePathAsOtherTask", Method.POST).get()
        .isMethodAllowed(Method.POST)).isTrue();
    assertThat(router.route("/samePathAsOtherTask", Method.POST).get()
        .isMethodAllowed(Method.GET)).isFalse();

    // notify request handler that the wrong method was used for the action.
    assertThat(router.route("/samePathAsOtherTask", Method.HEAD)).isPresent();
    assertThat(router.route("/samePathAsOtherTask", Method.HEAD).get()
        .isMethodAllowed(Method.HEAD)).isFalse();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/base-route/:parameter", method={Method.GET})
  public final class SingleRouteParamsTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/base-route/:parameter1/sub-route/:parameter2")
  public final class MultipleRouteParamsTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/base-route")
  public final class BaseRouteNoParamsTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface RouteParamsComponent {
    BaseRouteNoParamsTask noParams();
    SingleRouteParamsTask singleRouteParam();
    MultipleRouteParamsTask multipleRouteParams();
  }

  @Test
  public void testRouteParams_baseRoute_matchesRoute() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route", Method.GET);
    assertThat(baseRoute).isPresent();
    assertThat(baseRoute.get().prefix()).isEqualTo("/base-route");
    assertThat(baseRoute.get().pattern().matcher("/base-route").matches()).isTrue();
    assertThat(baseRoute.get().pattern().matcher("/base-route/hello").matches()).isFalse();
  }

  @Test
  public void testRouteParams_routeWithSingleParam_hasCorrectParams() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route/hello", Method.GET);
    assertThat(baseRoute).isPresent();
    Matcher m = baseRoute.get().pattern().matcher("/base-route/hello");
    m.matches();
    assertThat(m.group("parameter")).isEqualTo("hello");
  }

  @Test
  public void testRouteParams_routeWithSingleParam_matchesRoute() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route/hello", Method.GET);
    assertThat(baseRoute).isPresent();
    assertThat(baseRoute.get().pattern().matcher("/base-route/hello").matches()).isTrue();
    assertThat(baseRoute.get().pattern().matcher("/base-route").matches()).isFalse();
    assertThat(baseRoute.get().pattern().matcher("/base-route/hello/sub-route/world").matches())
      .isFalse();
  }

  @Test
  public void testRouteParams_routeWithMultipleParams_hasCorrectParams() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route/hello/sub-route/world", Method.GET);
    assertThat(baseRoute).isPresent();
    Matcher m = baseRoute.get().pattern().matcher("/base-route/hello/sub-route/world");
    m.matches();
    assertThat(m.group("parameter1")).isEqualTo("hello");
    assertThat(m.group("parameter2")).isEqualTo("world");
  }

  @Test
  public void testRouteParams_routeWithMultipleParams_matchesRoute() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route/hello/sub-route/world", Method.GET);
    assertThat(baseRoute).isPresent();
    assertThat(baseRoute.get().pattern().matcher("/base-route/hello/sub-route/world").matches())
      .isTrue();
    assertThat(baseRoute.get().pattern().matcher("/base-route").matches()).isFalse();
    assertThat(baseRoute.get().pattern().matcher("/base-route/hello").matches()).isFalse();
  }

  @Test
  public void testRouteParams_incorrectMethod_matchesButMethodNotAllowed() throws Exception {
    Router router = create(RouteParamsComponent.class);
    Optional<Route> baseRoute = router.route("/base-route/hello/sub-route/world", Method.POST);
    assertThat(baseRoute).isPresent();
    assertThat(baseRoute.get().isMethodAllowed(Method.POST)).isFalse();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/foo/:bar")
  public final class DuplicateRouteWithParamsTask1 implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/foo/:bazz")
  public final class DuplicateRouteWithParamsTask2 implements Runnable {
    @Override
    public void run() {}
  }

  public interface DuplicateRoutesWithParams {
    DuplicateRouteWithParamsTask1 task1();
    DuplicateRouteWithParamsTask2 task2();
  }

  @Test
  public void testRouteParams_duplicatePathsWithParams_throwsException() throws Exception {
    // this is thrown when the router attempts to add task2 Route.
    thrown.expect(IllegalArgumentException.class,
        "Two actions cannot have the same path and method");
    create(DuplicateRoutesWithParams.class);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Action(path = "/:foo/bar")
  public final class RouteParameterPrefixTask implements Runnable {
    @Override
    public void run() {}
  }

  public interface RouteParameterPrefixComponent {
    RouteParameterPrefixTask task();
  }

  @Test
  public void testRouteParams_routeParameterPrefix_throwsException() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "Route cannot begin with a route parameter");
    create(RouteParameterPrefixComponent.class);
  }
}
