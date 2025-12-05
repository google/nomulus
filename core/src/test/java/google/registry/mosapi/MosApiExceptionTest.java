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

import static com.google.common.truth.Truth.assertThat;

import google.registry.mosapi.MosApiException.DateDurationInvalidException;
import google.registry.mosapi.MosApiException.DateOrderInvalidException;
import google.registry.mosapi.MosApiException.EndDateSyntaxInvalidException;
import google.registry.mosapi.MosApiException.MosApiAuthorizationException;
import google.registry.mosapi.MosApiException.StartDateSyntaxInvalidException;
import google.registry.mosapi.model.MosApiErrorResponse;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MosApiException}. */
public class MosApiExceptionTest {

  @Test
  void testConstructor_withErrorResponse() {
    MosApiErrorResponse errorResponse =
        new MosApiErrorResponse("1234", "Test Message", "Test Description");
    MosApiException exception = new MosApiException(errorResponse);
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("MoSAPI returned an error (code: 1234): Test Message");
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }

  @Test
  void testConstructor_withMessageAndCause() {
    RuntimeException cause = new RuntimeException("Root Cause");
    MosApiException exception = new MosApiException("Wrapper Message", cause);
    assertThat(exception).hasMessageThat().isEqualTo("Wrapper Message");
    assertThat(exception).hasCauseThat().isEqualTo(cause);
    assertThat(exception.getErrorResponse()).isEmpty();
  }

  @Test
  void testAuthorizationException() {
    MosApiAuthorizationException exception = new MosApiAuthorizationException("Unauthorized");
    assertThat(exception).isInstanceOf(MosApiException.class);
    assertThat(exception).hasMessageThat().isEqualTo("Unauthorized");
    assertThat(exception.getErrorResponse()).isEmpty();
  }

  @Test
  void testCreate_forDateDurationInvalid() {
    MosApiErrorResponse errorResponse =
        new MosApiErrorResponse("2011", "Duration invalid", "Description");
    MosApiException exception = MosApiException.create(errorResponse);
    assertThat(exception).isInstanceOf(DateDurationInvalidException.class);
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }

  @Test
  void testCreate_forDateOrderInvalid() {
    MosApiErrorResponse errorResponse =
        new MosApiErrorResponse("2012", "End date before start date", "Description");
    MosApiException exception = MosApiException.create(errorResponse);
    assertThat(exception).isInstanceOf(DateOrderInvalidException.class);
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }

  @Test
  void testCreate_forStartDateSyntaxInvalid() {
    MosApiErrorResponse errorResponse =
        new MosApiErrorResponse("2013", "Invalid start date format", "Description");
    MosApiException exception = MosApiException.create(errorResponse);
    assertThat(exception).isInstanceOf(StartDateSyntaxInvalidException.class);
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }

  @Test
  void testCreate_forEndDateSyntaxInvalid() {
    MosApiErrorResponse errorResponse =
        new MosApiErrorResponse("2014", "Invalid end date format", "Description");
    MosApiException exception = MosApiException.create(errorResponse);
    assertThat(exception).isInstanceOf(EndDateSyntaxInvalidException.class);
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }

  @Test
  void testCreate_forUnknownCode() {
    MosApiErrorResponse errorResponse = new MosApiErrorResponse("9999", "Unknown", "Description");
    MosApiException exception = MosApiException.create(errorResponse);
    assertThat(exception.getClass()).isEqualTo(MosApiException.class);
    assertThat(exception.getErrorResponse()).hasValue(errorResponse);
  }
}
