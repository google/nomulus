// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FakeHttpServletRequest}. */
class FakeHttpServletRequestTest {

  private FakeHttpServletRequest request;

  @BeforeEach
  void setUp() {
    request = new FakeHttpServletRequest();
  }

  @Test
  void testHeaders() {
    request.setHeader("X-My-Header", "Value1");
    assertThat(request.getHeader("X-My-Header")).isEqualTo("Value1");
    assertThat(Collections.list(request.getHeaders("X-My-Header"))).containsExactly("Value1");
    assertThat(Collections.list(request.getHeaderNames())).containsExactly("X-My-Header");
  }

  @Test
  void testIntHeader() {
    request.setHeader("X-Int-Header", "42");
    assertThat(request.getIntHeader("X-Int-Header")).isEqualTo(42);
    assertThat(request.getIntHeader("Missing")).isEqualTo(-1);
  }

  @Test
  void testMethodAndUri() {
    request.setMethod("GET");
    request.setRequestUri("/test/path");
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getRequestURI()).isEqualTo("/test/path");
  }

  @Test
  void testBodyAndReader() throws IOException {
    String payload = "test body";
    request.setBody(payload.getBytes(UTF_8));

    BufferedReader reader = request.getReader();
    assertThat(reader.readLine()).isEqualTo(payload);
    assertThat(request.getContentLength()).isEqualTo(payload.length());
  }

  @Test
  void testAttributes() {
    request.setAttribute("attr1", "val1");
    assertThat(request.getAttribute("attr1")).isEqualTo("val1");
    assertThat(Collections.list(request.getAttributeNames())).containsExactly("attr1");

    request.removeAttribute("attr1");
    assertThat(request.getAttribute("attr1")).isNull();
  }
}
