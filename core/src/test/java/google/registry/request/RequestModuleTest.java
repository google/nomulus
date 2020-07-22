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
import static google.registry.request.RequestModule.provideJsonPayload;
import static org.junit.Assert.assertThrows;

import com.google.common.net.MediaType;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestModule}. */
final class RequestModuleTest {

  @Test
  void testProvideJsonPayload() {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":\"v\"}")).containsExactly("k", "v");
  }

  @Test
  void testProvideJsonPayload_contentTypeWithoutCharsetAllowed() {
    assertThat(provideJsonPayload(MediaType.JSON_UTF_8.withoutParameters(), "{\"k\":\"v\"}"))
        .containsExactly("k", "v");
  }

  @Test
  void testProvideJsonPayload_malformedInput_throws500() {
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> provideJsonPayload(MediaType.JSON_UTF_8, "{\"k\":"));
    assertThat(thrown).hasMessageThat().contains("Malformed JSON");
  }

  @Test
  void testProvideJsonPayload_emptyInput_throws500() {
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> provideJsonPayload(MediaType.JSON_UTF_8, ""));
    assertThat(thrown).hasMessageThat().contains("Malformed JSON");
  }

  @Test
  void testProvideJsonPayload_nonJsonContentType_throws415() {
    assertThrows(
        UnsupportedMediaTypeException.class,
        () -> provideJsonPayload(MediaType.PLAIN_TEXT_UTF_8, "{}"));
  }

  @Test
  void testProvideJsonPayload_contentTypeWithWeirdParam_throws415() {
    assertThrows(
        UnsupportedMediaTypeException.class,
        () -> provideJsonPayload(MediaType.JSON_UTF_8.withParameter("omg", "handel"), "{}"));
  }
}
