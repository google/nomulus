// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package google.registry.mosapi.model;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MosApiErrorResponse}. */
public class MosApiErrorResponseTest {

  @Test
  void testJsonDeserialization() {
    String json =
        """
        {
          "resultCode": "2012",
          "message": "The endDate is before the startDate.",
          "description": "Validation failed"
        }
        """;

    MosApiErrorResponse response = new Gson().fromJson(json, MosApiErrorResponse.class);

    assertThat(response.resultCode()).isEqualTo("2012");
    assertThat(response.message()).isEqualTo("The endDate is before the startDate.");
    assertThat(response.description()).isEqualTo("Validation failed");
  }
}
