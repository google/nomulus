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

package google.registry.mosapi.exception;

import google.registry.mosapi.dto.MosApiErrorResponse;
import java.io.IOException;

/** Custom exception for MoSAPI client errors. */
public class MosApiException extends IOException {

  public MosApiException(String message) {
    super(message);
  }

  public MosApiException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Thrown when MoSAPI returns a 401 Unauthorized error. */
  public static class MosApiAuthorizationException extends MosApiException {

    public MosApiAuthorizationException(String message) {
      super(message);
    }
  }

  /** Creates a specific exception message based on the MoSAPI error response. */
  public static MosApiException create(MosApiErrorResponse errorResponse) {
    String message =
        switch (errorResponse.resultCode()) {
          case "2012" -> "Date order is invalid: " + errorResponse.message();
          case "2013", "2014" -> "Date syntax is invalid: " + errorResponse.message();
          default ->
              String.format(
                  "Bad Request (code: %s): %s",
                  errorResponse.resultCode(), errorResponse.message());
        };
    return new MosApiException(message);
  }
}
