// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.api;

import static jakarta.servlet.http.HttpServletResponse.SC_REQUEST_TIMEOUT;

public class BsaException extends RuntimeException {

  private static final int SC_TOO_MANY_REQUESTS = 429;

  private final boolean retriable;

  public BsaException(Throwable cause, boolean retriable) {
    super(cause);
    this.retriable = retriable;
  }

  public BsaException(String message, boolean retriable) {
    super(message);
    this.retriable = retriable;
  }

  public boolean isRetriable() {
    return this.retriable;
  }

  /**
   * Returns whether an unsuccessful HTTP status code represents a transient failure that should be
   * retried ({@code 408}, {@code 429}, or {@code 5xx}) rather than a permanent client/request error
   * (such as {@code 400}, {@code 401}, {@code 403}, {@code 404}, or {@code 422}).
   */
  public static boolean isRetriableHttpStatus(int statusCode) {
    return statusCode == SC_REQUEST_TIMEOUT
        || statusCode == SC_TOO_MANY_REQUESTS
        || (statusCode >= 500 && statusCode < 600);
  }
}
