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
import static google.registry.request.RequestModule.provideJsonBody;
import static google.registry.request.RequestModule.provideJsonPayload;
import static google.registry.request.RequestModule.providePayloadAsBytes;
import static google.registry.request.RequestModule.providePayloadAsString;
import static google.registry.security.JsonHttpTestUtils.createServletInputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.PayloadTooLargeException;
import google.registry.request.HttpException.UnsupportedMediaTypeException;
import google.registry.tools.GsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestModule}. */
final class RequestModuleTest {

  private static final Gson GSON = GsonUtils.provideGson();

  @Test
  void testProvideJsonPayload() {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":\"v\"}", GSON))
        .containsExactly("k", "v");
  }

  @Test
  void testProvideJsonPayload_contentTypeWithoutCharsetAllowed() {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8.withoutParameters(), "{\"k\":\"v\"}", GSON))
        .containsExactly("k", "v");
  }

  @Test
  void testProvideJsonPayload_malformedInput_throws500() {
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":", GSON));
    assertThat(thrown).hasMessageThat().contains("Malformed JSON");
  }

  @Test
  void testProvideJsonPayload_emptyInput_throws500() {
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> provideJsonPayload(MediaType.JSON_UTF_8, "", GSON));
    assertThat(thrown).hasMessageThat().contains("Malformed JSON");
  }

  @Test
  void testProvideJsonPayload_nonJsonContentType_throws415() {
    assertThrows(
        UnsupportedMediaTypeException.class,
        () -> provideJsonPayload(MediaType.PLAIN_TEXT_UTF_8, "{}", GSON));
  }

  @Test
  void testProvideJsonPayload_contentTypeWithWeirdParam_throws415() {
    assertThrows(
        UnsupportedMediaTypeException.class,
        () -> provideJsonPayload(MediaType.JSON_UTF_8.withParameter("omg", "handel"), "{}", GSON));
  }

  @Test
  void testProvidePayloadAsBytes_contentLengthExceedsLimit_throws413() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContentLengthLong()).thenReturn((long) RequestModule.MAX_PAYLOAD_BYTES + 1);
    PayloadTooLargeException thrown =
        assertThrows(PayloadTooLargeException.class, () -> providePayloadAsBytes(req));
    assertThat(thrown).hasMessageThat().contains("exceeds limit");
  }

  @Test
  void testProvidePayloadAsBytes_streamExceedsLimit_throws413() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContentLengthLong()).thenReturn(-1L);
    when(req.getInputStream())
        .thenReturn(createServletInputStream(new byte[RequestModule.MAX_PAYLOAD_BYTES + 1]));
    PayloadTooLargeException thrown =
        assertThrows(PayloadTooLargeException.class, () -> providePayloadAsBytes(req));
    assertThat(thrown).hasMessageThat().contains("exceeds maximum allowed size");
  }

  @Test
  void testProvidePayloadAsString_invalidCharset_throws415() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getCharacterEncoding()).thenReturn("invalid-charset-name");
    UnsupportedMediaTypeException thrown =
        assertThrows(
            UnsupportedMediaTypeException.class,
            () -> providePayloadAsString("hello".getBytes(UTF_8), req));
    assertThat(thrown).hasMessageThat().contains("Unsupported charset: invalid-charset-name");
  }

  @Test
  void testProvideJsonBody_contentLengthExceedsLimit_throws413() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContentLengthLong()).thenReturn((long) RequestModule.MAX_PAYLOAD_BYTES + 1);
    PayloadTooLargeException thrown =
        assertThrows(
            PayloadTooLargeException.class,
            () -> provideJsonBody(providePayloadAsString(providePayloadAsBytes(req), req), GSON));
    assertThat(thrown).hasMessageThat().contains("exceeds limit");
  }
}
