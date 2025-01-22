// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Used when HTTP requests return a bad response, with troubleshooting info.
 *
 * <p>This class displays lots of helpful troubleshooting information.
 */
public class UrlConnectionException extends RuntimeException {

  private final HttpURLConnection connection;

  public UrlConnectionException(String message, HttpURLConnection connection) {
    super(message);
    this.connection = connection;
  }

  @Override
  public String getMessage() {
    byte[] resultContent;
    int responseCode = 0;
    try {
      responseCode = connection.getResponseCode();
      resultContent =
          ByteStreams.toByteArray(
              responseCode < 400 ? connection.getInputStream() : connection.getErrorStream());
    } catch (IOException | NullPointerException e) {
      resultContent = "-- Response is missing or has malformed content --".getBytes(UTF_8);
    }
    StringBuilder result =
        new StringBuilder(2048 + resultContent.length)
            .append(
                String.format(
                    "%s: %s (HTTP Status %d)\nX-Fetch-URL: %s\n",
                    getClass().getSimpleName(),
                    super.getMessage(),
                    responseCode,
                    connection.getURL().toString()));
    connection
        .getRequestProperties()
        .forEach(
            (key, value) -> {
              result.append(key);
              result.append(": ");
              result.append(value);
              result.append('\n');
            });
    result.append(">>>\n");
    result.append(new String(resultContent, UTF_8));
    result.append("\n<<<");
    return result.toString();
  }
}
