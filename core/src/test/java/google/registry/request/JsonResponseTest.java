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
import google.registry.testing.FakeResponse;
import java.util.Map;
import org.json.simple.JSONValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JsonResponse}. */
@RunWith(JUnit4.class)
public class JsonResponseTest {

  FakeResponse fakeResponse = new FakeResponse();
  JsonResponse jsonResponse = new JsonResponse(fakeResponse);

  @Test
  public void testSetStatus() {
    jsonResponse.setStatus(666);
    assertThat(fakeResponse.getStatus()).isEqualTo(666);
  }

  @Test
  public void testSetResponseValue() {
    ImmutableMap<String, String> responseValues = ImmutableMap.of(
        "hello", "world",
        "goodbye", "cruel world");
    jsonResponse.setPayload(responseValues);
    String payload = fakeResponse.getPayload();
    assertThat(payload).startsWith(JSON_SAFETY_PREFIX);
    @SuppressWarnings("unchecked")
    Map<String, Object> responseMap = (Map<String, Object>)
        JSONValue.parse(payload.substring(JSON_SAFETY_PREFIX.length()));
    assertThat(responseMap).containsExactlyEntriesIn(responseValues);
  }

  @Test
  public void testSetHeader() {
    jsonResponse.setHeader("header", "value");
    Map<String, Object> headerMap = fakeResponse.getHeaders();
    assertThat(headerMap.size()).isEqualTo(1);
    assertThat(headerMap.get("header")).isEqualTo("value");
  }
}
