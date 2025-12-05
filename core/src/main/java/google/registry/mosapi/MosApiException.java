// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mosapi;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import google.registry.mosapi.model.MosApiErrorResponse;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;

/** Custom exception for MoSAPI client errors. */
public class MosApiException extends IOException {

  private final MosApiErrorResponse errorResponse;

  public MosApiException(MosApiErrorResponse errorResponse) {
    super(
        String.format(
            "MoSAPI returned an error (code: %s): %s",
            errorResponse.resultCode(), errorResponse.message()));
    this.errorResponse = errorResponse;
  }

  public MosApiException(String message, Throwable cause) {
    super(message, cause);
    this.errorResponse = null;
  }

  public Optional<MosApiErrorResponse> getErrorResponse() {
    return Optional.ofNullable(errorResponse);
  }

  /** Annotation for associating a MoSAPI result code with an exception subclass. */
  @Documented
  @Retention(RUNTIME)
  @Target(TYPE)
  public @interface MosApiResultCode {
    String value();
  }

  /** Thrown when MoSAPI returns a 401 Unauthorized error. */
  public static class MosApiAuthorizationException extends MosApiException {
    public MosApiAuthorizationException(String message) {
      super(message, null);
    }
  }

  /** Creates a specific exception based on the MoSAPI error response. */
  public static MosApiException create(MosApiErrorResponse errorResponse) {
    Optional<MosApiResponse> responseEnum = MosApiResponse.fromCode(errorResponse.resultCode());
    if (responseEnum.isPresent()) {
      return switch (responseEnum.get()) {
        case DATE_DURATION_INVALID -> new DateDurationInvalidException(errorResponse);
        case DATE_ORDER_INVALID -> new DateOrderInvalidException(errorResponse);
        case START_DATE_SYNTAX_INVALID -> new StartDateSyntaxInvalidException(errorResponse);
        case END_DATE_SYNTAX_INVALID -> new EndDateSyntaxInvalidException(errorResponse);
        default -> new MosApiException(errorResponse);
      };
    }
    return new MosApiException(errorResponse);
  }

  /** Thrown when the date duration in a MoSAPI request is invalid. */
  @MosApiResultCode("2011")
  public static class DateDurationInvalidException extends MosApiException {
    public DateDurationInvalidException(MosApiErrorResponse errorResponse) {
      super(errorResponse);
    }
  }

  /** Thrown when the date order in a MoSAPI request is invalid. */
  @MosApiResultCode("2012")
  public static class DateOrderInvalidException extends MosApiException {
    public DateOrderInvalidException(MosApiErrorResponse errorResponse) {
      super(errorResponse);
    }
  }

  /** Thrown when the startDate syntax in a MoSAPI request is invalid. */
  @MosApiResultCode("2013")
  public static class StartDateSyntaxInvalidException extends MosApiException {
    public StartDateSyntaxInvalidException(MosApiErrorResponse errorResponse) {
      super(errorResponse);
    }
  }

  /** Thrown when the endDate syntax in a MoSAPI request is invalid. */
  @MosApiResultCode("2014")
  public static class EndDateSyntaxInvalidException extends MosApiException {
    public EndDateSyntaxInvalidException(MosApiErrorResponse errorResponse) {
      super(errorResponse);
    }
  }
}
