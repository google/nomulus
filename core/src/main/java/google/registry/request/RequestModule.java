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

import static com.google.common.net.MediaType.JSON_UTF_8;
import static google.registry.model.tld.Registries.assertTldExists;
import static google.registry.model.tld.Registries.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfParameters;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;
import dagger.Module;
import dagger.Provides;
import google.registry.model.common.DatabaseMigrationStateSchedule.PrimaryDatabase;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.UnsupportedMediaTypeException;
import google.registry.request.auth.AuthResult;
import google.registry.request.lock.LockHandler;
import google.registry.request.lock.LockHandlerImpl;
import google.registry.util.RequestStatusChecker;
import google.registry.util.RequestStatusCheckerImpl;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

/** Dagger module for servlets. */
@Module
public final class RequestModule {

  private final HttpServletRequest req;
  private final HttpServletResponse rsp;
  private final AuthResult authResult;

  @VisibleForTesting
  public RequestModule(HttpServletRequest req, HttpServletResponse rsp) {
    this(req, rsp, AuthResult.NOT_AUTHENTICATED);
  }

  public RequestModule(
      HttpServletRequest req, HttpServletResponse rsp, AuthResult authResult) {
    this.req = req;
    this.rsp = rsp;
    this.authResult = authResult;
  }

  @Provides
  @Parameter(RequestParameters.PARAM_TLD)
  static String provideTld(HttpServletRequest req) {
    return assertTldExists(extractRequiredParameter(req, RequestParameters.PARAM_TLD));
  }

  @Provides
  @Parameter(RequestParameters.PARAM_TLDS)
  static ImmutableSet<String> provideTlds(HttpServletRequest req) {
    ImmutableSet<String> tlds = extractSetOfParameters(req, RequestParameters.PARAM_TLDS);
    assertTldsExist(tlds);
    return tlds;
  }

  @Provides
  @Parameter(RequestParameters.PARAM_DATABASE)
  static PrimaryDatabase provideDatabase(HttpServletRequest req) {
    return extractOptionalParameter(req, RequestParameters.PARAM_DATABASE)
        .map(String::toUpperCase)
        .map(PrimaryDatabase::valueOf)
        .orElse(tm().isOfy() ? PrimaryDatabase.DATASTORE : PrimaryDatabase.CLOUD_SQL);
  }

  @Provides
  static Response provideResponse(ResponseImpl response) {
    return response;
  }

  @Provides
  HttpSession provideHttpSession() {
    return req.getSession();
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
  AuthResult provideAuthResult() {
    return authResult;
  }

  @Provides
  @RequestUrl
  static String provideRequestUrl(HttpServletRequest req) {
    return req.getRequestURL().toString();
  }

  @Provides
  @RequestPath
  static String provideRequestPath(HttpServletRequest req) {
    return req.getRequestURI();
  }

  /**
   * Returns the part of this request's URL that calls the servlet.
   *
   * <p>This includes the path to the servlet, but does not include any extra path information or a
   * query string.
   */
  @Provides
  @FullServletPath
  static String provideFullServletPath(HttpServletRequest req) {
    // Include the port only if it differs from the default for the scheme.
    if ((req.getScheme().equals("http") && (req.getServerPort() == 80))
        || (req.getScheme().equals("https") && (req.getServerPort() == 443))) {
      return String.format("%s://%s%s", req.getScheme(), req.getServerName(), req.getServletPath());
    } else {
      return String.format(
          "%s://%s:%d%s",
          req.getScheme(), req.getServerName(), req.getServerPort(), req.getServletPath());
    }
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
  static LockHandler provideLockHandler(LockHandlerImpl lockHandler) {
    return lockHandler;
  }

  @Provides
  static RequestStatusChecker provideRequestStatusChecker(
      RequestStatusCheckerImpl requestStatusChecker) {
    return requestStatusChecker;
  }

  @Provides
  @RequestLogId
  static String provideRequestLogId(RequestStatusChecker requestStatusChecker) {
    return requestStatusChecker.getLogId();
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
