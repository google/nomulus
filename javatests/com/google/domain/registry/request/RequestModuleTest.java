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
import static com.google.domain.registry.request.RequestModule.provideJsonPayload;
import static com.google.domain.registry.request.RequestModule.provideRequestPathMatcher;
import static com.google.domain.registry.request.RequestModule.provideRoute;

import com.google.common.base.Optional;
import com.google.common.net.MediaType;
import com.google.domain.registry.request.Action.Method;
import com.google.domain.registry.request.HttpException.BadRequestException;
import com.google.domain.registry.request.HttpException.UnsupportedMediaTypeException;
import com.google.domain.registry.testing.ExceptionRule;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Matchers.isA;

import javax.servlet.http.HttpServletRequest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RequestModule}. */
@RunWith(MockitoJUnitRunner.class)
public final class RequestModuleTest {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Mock
  private Router router;

  @Mock
  private Route route;

  @Test
  public void testProvideJsonPayload() throws Exception {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":\"v\"}"))
        .containsExactly("k", "v");
  }

  @Test
  public void testProvideJsonPayload_contentTypeWithoutCharsetAllowed() throws Exception {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8.withoutParameters(), "{\"k\":\"v\"}"))
        .containsExactly("k", "v");
  }

  @Test
  public void testProvideJsonPayload_malformedInput_throws500() throws Exception {
    thrown.expect(BadRequestException.class, "Malformed JSON");
    provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":");
  }

  @Test
  public void testProvideJsonPayload_nonJsonContentType_throws415() throws Exception {
    thrown.expect(UnsupportedMediaTypeException.class);
    provideJsonPayload(MediaType.PLAIN_TEXT_UTF_8, "{}");
  }

  @Test
  public void testProvideJsonPayload_contentTypeWithWeirdParam_throws415() throws Exception {
    thrown.expect(UnsupportedMediaTypeException.class);
    provideJsonPayload(MediaType.JSON_UTF_8.withParameter("omg", "handel"), "{}");
  }

  @Test
  public void testProvideRequestPathMatcher() throws Exception {
    // set up mock objects. Router should resolve a route using
    // request uri and method, and the route's matcher should
    // provide the route parameters given the same path.
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/foo/bar");
    when(req.getMethod()).thenReturn("GET");
    Route route = mock(Route.class);

    Pattern pattern = Pattern.compile("\\Q/foo/\\E(?<parameter>[^/]+)");
    when(route.pattern()).thenReturn(pattern);

    Matcher matcher = provideRequestPathMatcher(Optional.of(route), "/foo/bar");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group("parameter")).isEqualTo("bar");
  }

  @Test
  public void testProvideRequestPathMatcher_noRoute_throwsException() throws Exception {
    thrown.expect(IllegalStateException.class,
        "Optional.get() cannot be called on an absent value");
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/foo/bar");
    when(req.getMethod()).thenReturn("GET");

    provideRequestPathMatcher(Optional.<Route>absent(), "/foo/bar");
  }

  @Test
  public void testProvideRoute() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/foo/bar");
    when(req.getMethod()).thenReturn("GET");
    when(router.route(isA(String.class), isA(Method.class))).thenReturn(Optional.of(route));

    Optional<Route> result = provideRoute(req, router);
    assertThat(result).hasValue(route);
  }

  @Test
  public void testProvideRouteInvalidMethod() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/foo/bar");
    when(req.getMethod()).thenReturn("TRACE");
    when(router.route(isA(String.class), isA(Method.class))).thenReturn(Optional.of(route));

    Optional<Route> result = provideRoute(req, router);
    assertThat(result).isAbsent();
  }
}
