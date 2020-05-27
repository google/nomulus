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

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import google.registry.request.HttpException.BadRequestException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Utilities for extracting parameters from HTTP requests. */
public final class RequestParameters {

  /** The standardized request parameter name used by any action taking a tld parameter. */
  public static final String PARAM_TLD = "tld";
  /** The standardized request parameter name used by any action taking multiple tld parameters. */
  public static final String PARAM_TLDS = "tlds";

  /**
   * Returns first GET or POST parameter associated with {@code name}.
   *
   * <p>For example, assume {@code name} is "bar". The following request URIs would cause this
   * method to yield the following results:
   *
   * <ul>
   *   <li>/foo?bar=hello → hello
   *   <li>/foo?bar=hello&amp;bar=there → hello
   *   <li>/foo?bar= → 400 error (empty)
   *   <li>/foo?bar=&amp;bar=there → 400 error (empty)
   *   <li>/foo → 400 error (absent)
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
    return Optional.ofNullable(emptyToNull(req.getParameter(name)));
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
          ? Optional.empty()
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
    String stringParam = req.getParameter(name);
    try {
      return Integer.parseInt(nullToEmpty(stringParam));
    } catch (NumberFormatException e) {
      throw new BadRequestException(
          String.format("Expected int for parameter %s but received %s", name, stringParam));
    }
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as a long.
   *
   * @throws BadRequestException if request parameter is absent, empty, or not a valid long
   */
  public static long extractLongParameter(HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return Long.parseLong(nullToEmpty(stringParam));
    } catch (NumberFormatException e) {
      throw new BadRequestException(
          String.format("Expected long for parameter %s but received %s", name, stringParam));
    }
  }

  /**
   * Returns all GET or POST parameters associated with {@code name}.
   *
   * <p>The parameter value is assumed to be a comma-delimited set of values - so tlds=com,net would
   * result in ImmutableSet.of("com", "net").
   *
   * <p>Empty strings are not supported, and are automatically removed from the result.
   *
   * <p>Both missing parameter and parameter with empty value result in an empty set.
   *
   * @param req the request that has the parameter
   * @param name the name of the parameter, should be in plural form (e.g. tlds=, not tld=)
   */
  public static ImmutableSet<String> extractSetOfParameters(HttpServletRequest req, String name) {
    // First we make sure the user didn't accidentally try to pass the "set of parameters" as
    // multiple tld=a&tld=b parameters instead of tld=a,b
    String[] parameters = req.getParameterValues(name);
    if (parameters != null && parameters.length > 1) {
      throw new BadRequestException(
          String.format(
              "Bad 'set of parameters' input! Received multiple values instead of single "
                  + "comma-delimited value for parameter %s",
              name));
    }
    // Now we parse the single parameter.
    // We use the req.getParameter(name) instead of parameters[0] to make tests more consistent (all
    // extractXxx read the data from req.getParameter, so mocking the parameter is consistent)
    String parameter = req.getParameter(name);
    if (parameter == null || parameter.isEmpty()) {
      return ImmutableSet.of();
    }
    return Splitter.on(',').splitToList(parameter).stream()
        .filter(s -> !s.isEmpty())
        .collect(toImmutableSet());
  }

  /**
   * Returns all GET or POST parameters associated with {@code name}.
   *
   * <p>The parameter value is assumed to be a comma-delimited set of values - so tlds=com,net would
   * result in ImmutableSet.of("com", "net").
   *
   * <p>Empty strings are not supported, and are automatically removed from the result.
   *
   * <p>Both missing parameter and parameter with empty value result in an empty set.
   *
   * @param req the request that has the parameter
   * @param enumClass the Class of the expected Enum type
   * @param name the name of the parameter, should be in plural form (e.g. tlds=, not tld=)
   * @throws BadRequestException if any of the comma-delimited values of the request parameter named
   *     {@code name} aren't equal to any of the values in {@code enumClass}
   */
  public static <C extends Enum<C>> Optional<C> extractOptionalEnumParameter(
      HttpServletRequest req, Class<C> enumClass, String name) {
    return extractOptionalParameter(req, name).map(value -> getEnumValue(enumClass, value, name));
  }

  /**
   * Returns the first GET or POST parameter associated with {@code name}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or not
   *     equal to any of the values in {@code enumClass}
   */
  public static <C extends Enum<C>> C extractEnumParameter(
      HttpServletRequest req, Class<C> enumClass, String name) {
    return getEnumValue(enumClass, extractRequiredParameter(req, name), name);
  }

  /**
   * Returns the first GET or POST parameter associated with {@code name}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or not
   *     equal to any of the values in {@code enumClass}
   */
  public static <C extends Enum<C>> ImmutableSet<C> extractSetOfEnumParameters(
      HttpServletRequest req, Class<C> enumClass, String name) {
    return extractSetOfParameters(req, name).stream()
        .map(value -> getEnumValue(enumClass, value, name))
        .collect(toImmutableSet());
  }

  /** Translates a string name into the enum value, or throws a BadRequestException. */
  private static <C extends Enum<C>> C getEnumValue(
      Class<C> enumClass, String value, String parameterName) {
    try {
      return Enum.valueOf(enumClass, Ascii.toUpperCase(value));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          String.format(
              "Invalid parameter %s: expected enum of type %s, but got '%s'",
              parameterName, enumClass.getSimpleName(), value));
    }
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as a boolean.
   *
   * @throws BadRequestException if request parameter is present but not a valid boolean
   */
  public static Optional<Boolean> extractOptionalBooleanParameter(
      HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    return isNullOrEmpty(stringParam)
        ? Optional.empty()
        : Optional.of(Boolean.valueOf(stringParam));
  }

  /**
   * Returns {@code true} iff the given parameter is present, not empty, and not {@code "false"}.
   *
   * <p>This considers a parameter with a non-existent value true, for situations where the request
   * URI is something like {@code /foo?bar}, where the mere presence of the {@code bar} parameter
   * without a value indicates that it's true.
   */
  public static boolean extractBooleanParameter(HttpServletRequest req, String name) {
    return req.getParameterMap().containsKey(name) && !equalsFalse(req.getParameter(name));
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an <a
   * href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ}, {@code
   * 2000-01-01T16:20:00Z}.
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
   * Returns first request parameter associated with {@code name} parsed as an <a
   * href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ}, {@code
   * 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if request parameter is present but not a valid {@link DateTime}.
   */
  public static Optional<DateTime> extractOptionalDatetimeParameter(
      HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return isNullOrEmpty(stringParam)
          ? Optional.empty()
          : Optional.of(DateTime.parse(stringParam));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  /**
   * Returns all GET or POST date parameters associated with {@code name}, or an empty set if none.
   *
   * <p>Dates are parsed as an <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code
   * 1984-12-18TZ}, {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if one of the parameter values is not a valid {@link DateTime}.
   */
  public static ImmutableSet<DateTime> extractSetOfDatetimeParameters(
      HttpServletRequest req, String name) {
    try {
      return extractSetOfParameters(req, name).stream()
          .filter(not(String::isEmpty))
          .map(DateTime::parse)
          .collect(toImmutableSet());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
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
    return Optional.ofNullable(req.getHeader(name));
  }

  private RequestParameters() {}
}
