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

import static com.google.common.html.HtmlEscapers.htmlEscaper;

import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.util.logging.Level;
import javax.servlet.http.HttpServletResponse;

/** Base for exceptions that cause an HTTP error response. */
public abstract class HttpException extends RuntimeException {

  // as per https://tools.ietf.org/html/rfc4918
  private static final int SC_UNPROCESSABLE_ENTITY = 422;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Level logLevel;

  private final int responseCode;

  protected HttpException(int responseCode, String message, Throwable cause, Level logLevel) {
    super(message, cause);
    this.responseCode = responseCode;
    this.logLevel = logLevel;
  }

  protected HttpException(int responseCode, String message, Throwable cause) {
    this(responseCode, message, cause, Level.INFO);
  }

  public final int getResponseCode() {
    return responseCode;
  }

  /**
   * Returns the string associated with a particular response code. Unlike {@link #getMessage()},
   * which is meant to describe the circumstances under which this particular exception occurred,
   * this method always returns the same string (e.g. "Not Found" for a 400 error), and so should
   * always be the same for a given subclass of {@link HttpException}.
   */
  public String getResponseCodeString() {
    return "Response Code " + responseCode;
  }

  /**
   * Transmits the error response to the client.
   *
   * <p>{@link #getMessage()} will be sent to the browser, whereas {@link #toString()} and
   * {@link #getCause()} will be logged.
   */
  public final void send(HttpServletResponse rsp) throws IOException {
    rsp.sendError(getResponseCode(), htmlEscaper().escape(getMessage()));
    logger.at(logLevel).withCause(getCause()).log("%s", this);
  }

  /**
   * Exception that causes a 204 response.
   *
   * <p>This is useful for App Engine task queue handlers that want to display an error, but don't
   * want the task to automatically retry, since the status code is less than 300.
   */
  public static final class NoContentException extends HttpException {
    public NoContentException(String message) {
      super(HttpServletResponse.SC_NO_CONTENT, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "No Content";
    }
  }

  /** Exception that causes a 304 response. */
  public static final class NotModifiedException extends HttpException {
    public NotModifiedException() {
      this("Not Modified");
    }

    public NotModifiedException(String message) {
      super(HttpServletResponse.SC_NOT_MODIFIED, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Not Modified";
    }
  }

  /** Exception that causes a 400 response. */
  public static final class BadRequestException extends HttpException {
    public BadRequestException(String message) {
      this(message, null);
    }

    public BadRequestException(String message, Throwable cause) {
      super(HttpServletResponse.SC_BAD_REQUEST, message, cause);
    }

    @Override
    public String getResponseCodeString() {
      return "Bad Request";
    }
  }

  /** Exception that causes a 403 response. */
  public static final class ForbiddenException extends HttpException {
    public ForbiddenException(String message) {
      super(HttpServletResponse.SC_FORBIDDEN, message, null);
    }

    public ForbiddenException(String message, Exception cause) {
      super(HttpServletResponse.SC_FORBIDDEN, message, cause);
    }

    @Override
    public String getResponseCodeString() {
      return "Forbidden";
    }
  }

  /** Exception that causes a 404 response. */
  public static final class NotFoundException extends HttpException {
    public NotFoundException() {
      this("Not found");
    }

    public NotFoundException(String message) {
      super(HttpServletResponse.SC_NOT_FOUND, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Not Found";
    }
  }

  /** Exception that causes a 405 response. */
  public static final class MethodNotAllowedException extends HttpException {
    public MethodNotAllowedException() {
      super(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed", null);
    }

    @Override
    public String getResponseCodeString() {
      return "Method Not Allowed";
    }
  }

  /** Exception that causes a 409 response. */
  public static final class ConflictException extends HttpException {
    public ConflictException(String message) {
      super(HttpServletResponse.SC_CONFLICT, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Conflict";
    }
  }

  /** Exception that causes a 415 response. */
  public static final class UnsupportedMediaTypeException extends HttpException {
    public UnsupportedMediaTypeException(String message) {
      super(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, message, null);
    }

    public UnsupportedMediaTypeException(String message, Throwable cause) {
      super(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, message, cause);
    }

    @Override
    public String getResponseCodeString() {
      return "Unsupported Media Type";
    }
  }

  /** Exception that causes a 422 response. */
  public static final class UnprocessableEntityException extends HttpException {
    public UnprocessableEntityException(String message) {
      super(SC_UNPROCESSABLE_ENTITY, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Unprocessable Entity";
    }
  }

  /** Exception that causes a 500 response. */
  public static final class InternalServerErrorException extends HttpException {
    public InternalServerErrorException(String message) {
      super(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message, null, Level.SEVERE);
    }

    public InternalServerErrorException(String message, Throwable cause) {
      super(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message, cause);
    }

    @Override
    public String getResponseCodeString() {
      return "Internal Server Error";
    }
  }

  /** Exception that causes a 501 response. */
  public static final class NotImplementedException extends HttpException {
    public NotImplementedException(String message) {
      super(HttpServletResponse.SC_NOT_IMPLEMENTED, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Not Implemented";
    }
  }

  /** Exception that causes a 503 response. */
  public static final class ServiceUnavailableException extends HttpException {
    public ServiceUnavailableException(String message) {
      super(HttpServletResponse.SC_SERVICE_UNAVAILABLE, message, null);
    }

    @Override
    public String getResponseCodeString() {
      return "Service Unavailable";
    }
  }
}
