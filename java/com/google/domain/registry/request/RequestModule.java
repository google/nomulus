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

import static com.google.common.net.MediaType.JSON_UTF_8;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Optional;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;
import com.google.domain.registry.request.Action.Method;
import com.google.domain.registry.request.HttpException.BadRequestException;
import com.google.domain.registry.request.HttpException.UnsupportedMediaTypeException;

import dagger.Module;
import dagger.Provides;

import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Dagger module for servlets. */
@Module
public final class RequestModule {

  private final HttpServletRequest req;
  private final HttpServletResponse rsp;

  public RequestModule(HttpServletRequest req, HttpServletResponse rsp) {
    this.req = req;
    this.rsp = rsp;
  }

  @Provides
  static Response provideResponse(ResponseImpl response) {
    return response;
  }

  @Provides
  HttpServletRequest provideHttpServletRequest() {
    return req;
  }

  @Provides
  HttpServletResponse provideHttpServletResponse() {
    return rsp;
  }

  @Provides
  @RequestPath
  static String provideRequestPath(HttpServletRequest req) {
    return req.getRequestURI();
  }

  @Provides
  @RequestPath
  static Matcher provideRequestPathMatcher(
      Optional<Route> route, @RequestPath String path) {
    return route.get().pattern().matcher(path);
  }

  @Provides
  static Optional<Route> provideRoute(HttpServletRequest req, Router router) {
    Method method;
    try {
      method = Method.valueOf(req.getMethod());
    } catch (IllegalArgumentException e) {
      // Ignore unrecognized method so RequestHandler can deal with it.
      return Optional.absent();
    }
    return router.route(req.getRequestURI(), method);
  }

  @Provides
  @RequestMethod
  static Action.Method provideRequestMethod(HttpServletRequest req) {
    return Action.Method.valueOf(req.getMethod());
  }

  @Provides
  @Header("Content-Type")
  static MediaType provideContentType(HttpServletRequest req) {
    try {
      return MediaType.parse(req.getContentType());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new UnsupportedMediaTypeException("Bad Content-Type header", e);
    }
  }

  @Provides
  @Payload
  static String providePayloadAsString(HttpServletRequest req) {
    try {
      return CharStreams.toString(req.getReader());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Payload
  static byte[] providePayloadAsBytes(HttpServletRequest req) {
    try {
      return ByteStreams.toByteArray(req.getInputStream());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @JsonPayload
  @SuppressWarnings("unchecked")
  static Map<String, Object> provideJsonPayload(
      @Header("Content-Type") MediaType contentType,
      @Payload String payload) {
    if (!JSON_UTF_8.is(contentType.withCharset(UTF_8))) {
      throw new UnsupportedMediaTypeException(
          String.format("Expected %s Content-Type", JSON_UTF_8.withoutParameters()));
    }
    try {
      return (Map<String, Object>) JSONValue.parseWithException(payload);
    } catch (ParseException e) {
      throw new BadRequestException(
          "Malformed JSON", new VerifyException("Malformed JSON:\n" + payload, e));
    }
  }

  /**
   * Provides an immutable representation of the servlet request parameters.
   *
   * <p>This performs a shallow copy of the {@code Map<String, String[]>} data structure from the
   * servlets API, each time this is provided. This is almost certainly less expensive than the
   * thread synchronization expense of {@link javax.inject.Singleton @Singleton}.
   *
   * <p><b>Note:</b> If a parameter is specified without a value, e.g. {@code /foo?lol} then an
   * empty string value is assumed, since Guava's multimap doesn't permit {@code null} mappings.
   *
   * @see HttpServletRequest#getParameterMap()
   */
  @Provides
  @ParameterMap
  static ImmutableListMultimap<String, String> provideParameterMap(HttpServletRequest req) {
    ImmutableListMultimap.Builder<String, String> params = new ImmutableListMultimap.Builder<>();
    @SuppressWarnings("unchecked")  // Safe by specification.
    Map<String, String[]> original = req.getParameterMap();
    for (Map.Entry<String, String[]> param : original.entrySet()) {
      if (param.getValue().length == 0) {
        params.put(param.getKey(), "");
      } else {
        params.putAll(param.getKey(), param.getValue());
      }
    }
    return params.build();
  }
}
