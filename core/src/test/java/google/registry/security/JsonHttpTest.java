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

package google.registry.security;

import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;
import static com.google.common.net.HttpHeaders.X_CONTENT_TYPE_OPTIONS;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_16;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JsonHttp}. */
class JsonHttpTest {

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);

  @Test
  void testRead_postMethod_works() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"k\":\"v\"}")));
    assertThat(JsonHttp.read(req)).containsEntry("k", "v");
  }

  @Test
  void testRead_putMethod_works() throws Exception {
    when(req.getMethod()).thenReturn("PUT");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"k\":\"v\"}")));
    assertThat(JsonHttp.read(req)).containsEntry("k", "v");
  }

  @Test
  void testRead_getMethod_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testRead_textPlainContentType_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(PLAIN_TEXT_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"k\":\"v\"}")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testRead_jsonContentTypeWithoutCharsetParameter_allowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.withoutParameters().toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"k\":\"v\"}")));
    assertThat(JsonHttp.read(req)).containsEntry("k", "v");
  }

  @Test
  void testRead_jsonContentTypeWithWeirdCharsetParameter_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.withCharset(UTF_16).toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"k\":\"v\"}")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testRead_emptyJson_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testRead_nonObjectJson_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("123")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testRead_nullJson_notAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getContentType()).thenReturn(JSON_UTF_8.toString());
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("null")));
    assertThat(JsonHttp.read(req)).isNull();
  }

  @Test
  void testWrite() throws Exception {
    StringWriter writer = new StringWriter();
    when(rsp.getWriter()).thenReturn(new PrintWriter(writer));
    JsonHttp.write(rsp, ImmutableMap.of("k", "v"));
    assertThat(writer.toString()).isEqualTo(")]}'\n{\"k\":\"v\"}");
    verify(rsp).setHeader(CONTENT_DISPOSITION, "attachment");
    verify(rsp).setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
    verify(rsp).setContentType(JSON_UTF_8.toString());
    verify(rsp).getWriter();
    verifyNoMoreInteractions(rsp);
  }
}
