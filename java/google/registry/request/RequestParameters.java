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

package google.registry.request;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;

import com.google.common.base.Ascii;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.request.HttpException.BadRequestException;
import java.net.InetAddress;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Utilities for extracting parameters from HTTP requests. */
public final class RequestParameters {

  /** The standardized request parameter name used by any servlet that takes a tld parameter. */
  public static final String PARAM_TLD = "tld";

  /**
   * Returns first GET or POST parameter associated with {@code name}.
   *
   * <p>For example, assume {@code name} is "bar". The following request URIs would cause this
   * method to yield the following results:
   *
   * <ul>
   * <li>/foo?bar=hello → hello
   * <li>/foo?bar=hello&bar=there → hello
   * <li>/foo?bar= → 400 error (empty)
   * <li>/foo?bar=&bar=there → 400 error (empty)
   * <li>/foo → 400 error (absent)
   * </ul>
   *
   * @throws BadRequestException if request parameter is absent or empty
   */
  public static String extractRequiredParameter(HttpServletRequest req, String name) {
    String result = req.getParameter(name);
    if (isNullOrEmpty(result)) {
      throw new BadRequestException("Missing parameter: " + name);
    }
    return result;
  }

  /** Returns the first GET or POST parameter associated with {@code name}. */
  public static Optional<String> extractOptionalParameter(HttpServletRequest req, String name) {
    return Optional.fromNullable(emptyToNull(req.getParameter(name)));
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as an integer.
   *
   * @throws BadRequestException if request parameter is present but not a valid integer
   */
  public static Optional<Integer> extractOptionalIntParameter(HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return isNullOrEmpty(stringParam)
          ? Optional.<Integer>absent()
          : Optional.of(Integer.valueOf(stringParam));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Expected integer: " + name);
    }
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as an integer.
   *
   * @throws BadRequestException if request parameter is absent, empty, or not a valid integer
   */
  public static int extractIntParameter(HttpServletRequest req, String name) {
    try {
      return Integer.valueOf(nullToEmpty(req.getParameter(name)));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Expected integer: " + name);
    }
  }

  /** Returns all GET or POST parameters associated with {@code name}. */
  public static ImmutableSet<String> extractSetOfParameters(HttpServletRequest req, String name) {
    String[] parameters = req.getParameterValues(name);
    return parameters == null ? ImmutableSet.<String>of() : ImmutableSet.copyOf(parameters);
  }

  /**
   * Returns the first GET or POST parameter associated with {@code name}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or not
   *     equal to any of the values in {@code enumClass}
   */
  public static <C extends Enum<C>>
      C extractEnumParameter(HttpServletRequest req, Class<C> enumClass, String name) {
    try {
      return Enum.valueOf(enumClass, Ascii.toUpperCase(extractRequiredParameter(req, name)));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          String.format("Invalid %s parameter: %s", enumClass.getSimpleName(), name));
    }
  }

  /**
   * Returns {@code true} if parameter is present and not empty and not {@code "false"}.
   *
   * <p>This considers a parameter with a non-existent value true, for situations where the request
   * URI is something like {@code /foo?bar}, where the mere presence of the {@code bar} parameter
   * without a value indicates that it's true.
   */
  public static boolean extractBooleanParameter(HttpServletRequest req, String name) {
    return req.getParameterMap().containsKey(name)
        && !equalsFalse(req.getParameter(name));
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an
   * <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ},
   * {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or could
   *     not be parsed as an ISO 8601 timestamp
   */
  public static DateTime extractRequiredDatetimeParameter(HttpServletRequest req, String name) {
    String stringValue = extractRequiredParameter(req, name);
    try {
      return DateTime.parse(stringValue);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an
   * <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ},
   * {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if request parameter is present but not a valid {@link DateTime}.
   */
  public static Optional<DateTime> extractOptionalDatetimeParameter(
      HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return isNullOrEmpty(stringParam)
          ? Optional.<DateTime>absent()
          : Optional.of(DateTime.parse(stringParam));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an optional
   * {@link InetAddress} (which might be IPv6).
   *
   * @throws BadRequestException if request parameter named {@code name} is present but could not
   *     be parsed as an {@link InetAddress}
   */
  public static Optional<InetAddress> extractOptionalInetAddressParameter(
      HttpServletRequest req, String name) {
    Optional<String> paramVal = extractOptionalParameter(req, name);
    if (!paramVal.isPresent()) {
      return Optional.absent();
    }
    try {
      return Optional.of(InetAddresses.forString(paramVal.get()));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Not an IPv4 or IPv6 address: " + name);
    }
  }

  private static boolean equalsFalse(@Nullable String value) {
    return nullToEmpty(value).equalsIgnoreCase("false");
  }

  /**
   * Returns first HTTP header associated with {@code name}.
   *
   * @param name case insensitive header name
   * @throws BadRequestException if request header is absent or empty
   */
  public static String extractRequiredHeader(HttpServletRequest req, String name) {
    String result = req.getHeader(name);
    if (isNullOrEmpty(result)) {
      throw new BadRequestException("Missing header: " + name);
    }
    return result;
  }

  /**
   * Returns an {@link Optional} of the first HTTP header associated with {@code name}, or empty.
   *
   * @param name case insensitive header name
   */
  public static Optional<String> extractOptionalHeader(HttpServletRequest req, String name) {
    return Optional.fromNullable(req.getHeader(name));
  }

  private RequestParameters() {}
}
