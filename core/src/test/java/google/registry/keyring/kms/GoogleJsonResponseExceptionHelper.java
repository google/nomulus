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

package google.registry.keyring.kms;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.jackson2.JacksonFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A helper to create instances of {@link GoogleJsonResponseException}. */
public class GoogleJsonResponseExceptionHelper {
  /**
   * @param statusCode the status code that should be in the returned {@link
   *     GoogleJsonResponseException}
   * @return a {@link GoogleJsonResponseException} with the status code {@code statusCode}
   * @throws IOException shouldn't occur
   */
  public static GoogleJsonResponseException create(int statusCode) throws IOException {
    HttpResponse response = createHttpResponse(statusCode, null);
    return GoogleJsonResponseException.from(new JacksonFactory(), response);
  }

  public static HttpResponse createHttpResponse(int statusCode, InputStream content)
      throws IOException {
    FakeHttpTransport transport = new FakeHttpTransport(statusCode, content);
    HttpRequestFactory factory = transport.createRequestFactory();
    HttpRequest request =
        factory.buildRequest(
            "foo", new GenericUrl("http://example.com/bar"), new EmptyHttpContent());
    request.setThrowExceptionOnExecuteError(false);
    return request.execute();
  }

  private static class FakeHttpTransport extends HttpTransport {
    private final int statusCode;
    private final InputStream content;

    FakeHttpTransport(int statusCode, InputStream content) {
      this.statusCode = statusCode;
      this.content = content;
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
      return new FakeLowLevelHttpRequest(statusCode, content);
    }
  }

  private static class FakeLowLevelHttpRequest extends LowLevelHttpRequest {
    private final int statusCode;
    private final InputStream content;

    FakeLowLevelHttpRequest(int statusCode, InputStream content) {
      this.statusCode = statusCode;
      this.content = content;
    }

    @Override
    public void addHeader(String name, String value) {
      // Nothing!
    }

    @Override
    public LowLevelHttpResponse execute() {
      return new FakeLowLevelHttpResponse(statusCode, content);
    }
  }

  private static class FakeLowLevelHttpResponse extends LowLevelHttpResponse {
    private final int statusCode;
    private final InputStream content;

    FakeLowLevelHttpResponse(int statusCode, InputStream content) {
      this.statusCode = statusCode;
      this.content = content;
    }

    @Override
    public InputStream getContent() {
      return content;
    }

    @Override
    public String getContentEncoding() {
      return null;
    }

    @Override
    public long getContentLength() {
      return 0;
    }

    @Override
    public String getContentType() {
      return "text/json";
    }

    @Override
    public String getStatusLine() {
      return null;
    }

    @Override
    public int getStatusCode() {
      return statusCode;
    }

    @Override
    public String getReasonPhrase() {
      return null;
    }

    @Override
    public int getHeaderCount() {
      return 0;
    }

    @Override
    public String getHeaderName(int index) {
      return null;
    }

    @Override
    public String getHeaderValue(int index) {
      return null;
    }
  }

  private static class EmptyHttpContent implements HttpContent {
    @Override
    public long getLength() {
      return 0;
    }

    @Override
    public String getType() {
      return "text/json";
    }

    @Override
    public boolean retrySupported() {
      return false;
    }

    @Override
    public void writeTo(OutputStream out) {
      // Nothing!
    }
  }
}
