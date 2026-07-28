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
import static jakarta.servlet.http.HttpServletResponse.SC_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.PrintWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FakeHttpServletResponse}. */
class FakeHttpServletResponseTest {

  private FakeHttpServletResponse response;

  @BeforeEach
  void setUp() {
    response = new FakeHttpServletResponse();
  }

  @Test
  void testStatus() throws IOException {
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    response.setStatus(400);
    assertThat(response.getStatus()).isEqualTo(400);

    response.sendError(500);
    assertThat(response.getStatus()).isEqualTo(500);

    response.sendRedirect("/redirect");
    assertThat(response.getStatus()).isEqualTo(SC_FOUND);
  }

  @Test
  void testContentType() {
    response.setContentType("text/plain");
    assertThat(response.getContentType()).isEqualTo("text/plain");
  }

  @Test
  void testHeaders() {
    response.setHeader("X-Test", "Val");
    assertThat(response.getHeader("X-Test")).isEqualTo("Val");
    assertThat(response.getHeaders("X-Test")).containsExactly("Val");
    assertThat(response.getHeaderNames()).containsExactly("X-Test");
    assertThat(response.containsHeader("X-Test")).isTrue();
  }

  @Test
  void testMultipleHeaders() {
    response.setHeader("X-Test", "Val1");
    response.addHeader("X-Test", "Val2");
    assertThat(response.getHeader("X-Test")).isEqualTo("Val1");
    assertThat(response.getHeaders("X-Test")).containsExactly("Val1", "Val2");

    response.setHeader("X-Test", "Val3");
    assertThat(response.getHeader("X-Test")).isEqualTo("Val3");
    assertThat(response.getHeaders("X-Test")).containsExactly("Val3");
  }

  @Test
  void testWriterAndPayload() throws IOException {
    PrintWriter writer = response.getWriter();
    writer.print("hello world");

    assertThat(new String(response.getPayload(), UTF_8)).isEqualTo("hello world");
  }

  @Test
  void testOutputStreamAndPayload() throws IOException {
    byte[] data = "test bytes".getBytes(UTF_8);
    response.getOutputStream().write(data);

    assertThat(response.getPayload()).isEqualTo(data);
  }

  @Test
  void testReset() throws IOException {
    response.setStatus(404);
    response.setHeader("A", "B");
    response.getWriter().print("content");

    response.reset();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getHeaderNames()).isEmpty();
    assertThat(response.getPayload()).isEmpty();
  }

  @Test
  void testSendRedirectWithStatus() throws IOException {
    response.sendRedirect("/redirect", 301, false);
    assertThat(response.getStatus()).isEqualTo(301);
  }

  @Test
  void testSendEarlyHints() {
    response.sendEarlyHints(); // Should not throw
  }
}
