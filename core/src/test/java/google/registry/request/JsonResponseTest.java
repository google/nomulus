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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.JsonResponse.JSON_SAFETY_PREFIX;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JsonResponse}. */
class JsonResponseTest {

  private static final Gson GSON = GsonUtils.provideGson();

  private FakeResponse fakeResponse = new FakeResponse();
  private JsonResponse jsonResponse = new JsonResponse(fakeResponse, GSON);

  @Test
  void testSetStatus() {
    jsonResponse.setStatus(666);
    assertThat(fakeResponse.getStatus()).isEqualTo(666);
  }

  @Test
  void testSetResponseValue() {
    ImmutableMap<String, String> responseValues = ImmutableMap.of(
        "hello", "world",
        "goodbye", "cruel world");
    jsonResponse.setPayload(responseValues);
    String payload = fakeResponse.getPayload();
    assertThat(payload).startsWith(JSON_SAFETY_PREFIX);
    Map<String, Object> responseMap =
        GSON.fromJson(payload.substring(JSON_SAFETY_PREFIX.length()), new TypeToken<>() {});
    assertThat(responseMap).containsExactlyEntriesIn(responseValues);
  }

  @Test
  void testSetHeader() {
    jsonResponse.setHeader("header", "value");
    Map<String, Object> headerMap = fakeResponse.getHeaders();
    assertThat(headerMap).hasSize(1);
    assertThat(headerMap.get("header")).isEqualTo("value");
  }

  @Test
  void testSetDateHeader() {
    Instant timestamp = Instant.parse("2024-03-27T10:15:30.105Z");
    jsonResponse.setDateHeader("header", timestamp);
    assertThat(fakeResponse.getHeaders()).containsEntry("header", timestamp);
  }
}
