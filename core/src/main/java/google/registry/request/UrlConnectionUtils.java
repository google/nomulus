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

package google.registry.request;

import static com.google.common.io.BaseEncoding.base64;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.net.MediaType;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URLConnection;

public class UrlConnectionUtils {

  public static void setBasicAuth(URLConnection connection, String username, String password) {
    String token = base64().encode(String.format("%s:%s", username, password).getBytes(UTF_8));
    connection.setRequestProperty(AUTHORIZATION, "Basic " + token);
  }

  public static void writePayload(URLConnection connection, byte[] bytes, MediaType contentType)
      throws IOException {
    connection.setRequestProperty(CONTENT_TYPE, contentType.toString());
    connection.setDoOutput(true);
    try (DataOutputStream dataStream = new DataOutputStream(connection.getOutputStream())) {
      dataStream.write(bytes);
    }
  }
}
