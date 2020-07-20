// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.security.JsonHttp.JSON_SAFETY_PREFIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link google.registry.tools.AppEngineConnection}. */
@ExtendWith(MockitoExtension.class)
final class AppEngineConnectionTest {

  private AppEngineConnection connection;
  private TestHttpTransport httpTransport;
  private TestLowLevelHttpRequest lowLevelHttpRequest;
  @Mock LowLevelHttpResponse lowLevelHttpResponse;

  private final class TestHttpTransport extends HttpTransport {
    String method = null;
    String url = null;

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
      // Make sure we only visit once
      assertThat(this.method).isNull();
      this.method = method;
      this.url = url;
      return lowLevelHttpRequest;
    }
  }

  private final class TestLowLevelHttpRequest extends LowLevelHttpRequest {
    final HashMap<String, String> headers = new HashMap<>();

    @Override
    public void addHeader(String name, String value) {
      headers.put(name, value);
    }

    @Override
    public LowLevelHttpResponse execute() {
      return lowLevelHttpResponse;
    }

    String getContentString() throws Exception {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      getStreamingContent().writeTo(output);
      output.close();
      return new String(output.toByteArray(), UTF_8);
    }
  }

  @BeforeEach
  void beforeEach() throws Exception {
    lowLevelHttpRequest = new TestLowLevelHttpRequest();
    when(lowLevelHttpResponse.getContent())
        .thenReturn(new ByteArrayInputStream("MyContent".getBytes(UTF_8)));
    when(lowLevelHttpResponse.getStatusCode()).thenReturn(200);

    connection = new AppEngineConnection();
    httpTransport = new TestHttpTransport();
    connection.requestFactory = httpTransport.createRequestFactory();
  }

  @Test
  void testSendGetRequest() throws Exception {
    assertThat(
            connection.sendGetRequest(
                "/my/path?query", ImmutableMap.of("key1", "value1", "key2", "value2")))
        .isEqualTo("MyContent");
    assertThat(httpTransport.method).isEqualTo("GET");
    assertThat(httpTransport.url)
        .isEqualTo("https://localhost/my/path?query&key1=value1&key2=value2");
    assertThat(lowLevelHttpRequest.headers).containsEntry("Cache-Control", "no-cache");
    assertThat(lowLevelHttpRequest.headers).containsEntry("x-requested-with", "RegistryTool");
  }

  @Test
  void testSendPostRequest() throws Exception {
    assertThat(
            connection.sendPostRequest(
                "/my/path?query",
                ImmutableMap.of("key1", "value1", "key2", "value2"),
                MediaType.PLAIN_TEXT_UTF_8,
                "some data".getBytes(UTF_8)))
        .isEqualTo("MyContent");
    assertThat(httpTransport.method).isEqualTo("POST");
    assertThat(httpTransport.url)
        .isEqualTo("https://localhost/my/path?query&key1=value1&key2=value2");
    assertThat(lowLevelHttpRequest.getContentType()).isEqualTo("text/plain; charset=utf-8");
    assertThat(lowLevelHttpRequest.getContentString()).isEqualTo("some data");
    assertThat(lowLevelHttpRequest.headers).containsEntry("Cache-Control", "no-cache");
    assertThat(lowLevelHttpRequest.headers).containsEntry("x-requested-with", "RegistryTool");
  }

  @Test
  void testSendJsonRequest() throws Exception {
    when(lowLevelHttpResponse.getContent())
        .thenReturn(
            new ByteArrayInputStream((JSON_SAFETY_PREFIX + "{\"key\":\"value\"}").getBytes(UTF_8)));
    assertThat(
            connection.sendJson(
                "/my/path?query", ImmutableMap.of("string", "value1", "bool", true)))
        .containsExactly("key", "value");
    assertThat(httpTransport.method).isEqualTo("POST");
    assertThat(httpTransport.url).isEqualTo("https://localhost/my/path?query");
    assertThat(lowLevelHttpRequest.getContentType()).isEqualTo("application/json; charset=utf-8");
    assertThat(lowLevelHttpRequest.getContentString())
        .isEqualTo("{\"string\":\"value1\",\"bool\":true}");
    assertThat(lowLevelHttpRequest.headers).containsEntry("Cache-Control", "no-cache");
    assertThat(lowLevelHttpRequest.headers).containsEntry("x-requested-with", "RegistryTool");
  }
}
