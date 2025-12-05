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
package google.registry.mosapi.service;

import static com.google.common.truth.Truth.assertThat;

import google.registry.mosapi.dto.MosApiErrorResponse;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.exception.MosApiException.MosApiAuthorizationException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MosApiException}. */
public class MosApiExceptionTest {
  @Test
  void testConstructor_messageOnly() {
    MosApiException exception = new MosApiException("Something went wrong");
    assertThat(exception).hasMessageThat().isEqualTo("Something went wrong");
  }

  @Test
  void testConstructor_messageAndCause() {
    Throwable cause = new RuntimeException("Root cause");
    MosApiException exception = new MosApiException("Wrapper message", cause);

    assertThat(exception).hasMessageThat().isEqualTo("Wrapper message");
    assertThat(exception).hasCauseThat().isEqualTo(cause);
  }

  @Test
  void testAuthorizationException() {
    MosApiAuthorizationException exception =
        new MosApiAuthorizationException("Unauthorized access");

    assertThat(exception).isInstanceOf(MosApiException.class);
    assertThat(exception).hasMessageThat().isEqualTo("Unauthorized access");
  }

  @Test
  void testCreate_code2012_dateOrderInvalid() {
    // Code 2012: Date order is invalid
    MosApiErrorResponse error =
        new MosApiErrorResponse("2012", "The endDate is before startDate", "Description");

    MosApiException exception = MosApiException.create(error);

    assertThat(exception).hasMessageThat().startsWith("Date order is invalid:");
    assertThat(exception).hasMessageThat().contains("The endDate is before startDate");
  }

  @Test
  void testCreate_code2013_dateSyntaxInvalid() {
    // Code 2013: Date syntax is invalid
    MosApiErrorResponse error =
        new MosApiErrorResponse("2013", "Invalid format YYYY", "Description");

    MosApiException exception = MosApiException.create(error);

    assertThat(exception).hasMessageThat().startsWith("Date syntax is invalid:");
    assertThat(exception).hasMessageThat().contains("Invalid format YYYY");
  }

  @Test
  void testCreate_code2014_dateSyntaxInvalid() {
    // Code 2014: Also Date syntax invalid
    MosApiErrorResponse error =
        new MosApiErrorResponse("2014", "Invalid characters", "Description");

    MosApiException exception = MosApiException.create(error);

    assertThat(exception).hasMessageThat().startsWith("Date syntax is invalid:");
    assertThat(exception).hasMessageThat().contains("Invalid characters");
  }

  @Test
  void testCreate_defaultCode_genericMessage() {
    // Default case: "Bad Request (code: ...): ..."
    MosApiErrorResponse error =
        new MosApiErrorResponse("400", "Generic bad request", "Description");

    MosApiException exception = MosApiException.create(error);

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("Bad Request (code: 400): Generic bad request");
  }
}
