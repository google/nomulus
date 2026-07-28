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

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.security.JsonHttp.JSON_SAFETY_PREFIX;

import com.google.common.base.Supplier;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import google.registry.tools.GsonUtils;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * Helper class for testing JSON RPC servlets.
 */
public final class JsonHttpTestUtils {

  private static final Gson GSON = GsonUtils.provideGson();

  /** Returns JSON payload for mocked result of {@code rsp.getReader()}. */
  public static BufferedReader createJsonPayload(Map<String, ?> object) {
    return createJsonPayload(GSON.toJson(object));
  }

  /** @see #createJsonPayload(Map) */
  public static BufferedReader createJsonPayload(String jsonText) {
    return new BufferedReader(new StringReader(jsonText));
  }

  /**
   * Returns JSON data parsed out of the contents of the given writer. If the data will be fetched
   * multiple times, consider {@link #createJsonResponseSupplier}.
   *
   * <p>Example Mockito usage:<pre>  {@code
   *
   *   StringWriter writer = new StringWriter();
   *   when(rsp.getWriter()).thenReturn(new PrintWriter(writer));
   *   servlet.service(req, rsp);
   *   assertThat(getJsonResponse(writer)).containsEntry("status", "SUCCESS");}</pre>
   */
  public static Map<String, Object> getJsonResponse(StringWriter writer) {
    String jsonText = writer.toString();
    assertThat(jsonText).startsWith(JSON_SAFETY_PREFIX);
    jsonText = jsonText.substring(JSON_SAFETY_PREFIX.length());
    try {
      return GSON.fromJson(jsonText, new TypeToken<>() {});
    } catch (ClassCastException | JsonSyntaxException e) {
      assertWithMessage("Bad JSON: %s\n%s", e.getMessage(), jsonText).fail();
      throw new AssertionError();
    }
  }

  /**
   * Returns a memoized supplier that'll provide the JSON response object of the tested servlet.
   *
   * <p>This works with Mockito as follows:<pre>   {@code
   *
   *   StringWriter writer = new StringWriter();
   *   Supplier<Map<String, Object>> json = createJsonResponseSupplier(writer);
   *   when(rsp.getWriter()).thenReturn(new PrintWriter(writer));
   *   servlet.service(req, rsp);
   *   assertThat(json.get()).containsEntry("status", "SUCCESS");}</pre>
   */
  public static Supplier<Map<String, Object>> createJsonResponseSupplier(
      final StringWriter writer) {
    return memoize(() -> getJsonResponse(writer));
  }

  public static ServletInputStream createServletInputStream(byte[] data) {
    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return bais.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {}

      @Override
      public int read() {
        return bais.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        return bais.read(b, off, len);
      }
    };
  }
}
