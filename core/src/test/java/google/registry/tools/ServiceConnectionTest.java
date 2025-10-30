// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.common.collect.ImmutableMap;
import google.registry.request.Action.Service;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link google.registry.tools.ServiceConnection}. */
public class ServiceConnectionTest {

  @Test
  void testSuccess_serverUrl_notCanary() {
    ServiceConnection connection =
        new ServiceConnection(false, null).withService(Service.FRONTEND, false);
    String serverUrl = connection.getServer().toString();
    assertThat(serverUrl).isEqualTo("https://frontend.registry.test"); // See default-config.yaml
  }

  @Test
  void testSuccess_serverUrl_gke_canary() throws Exception {
    HttpRequestFactory factory = mock(HttpRequestFactory.class);
    HttpRequest request = mock(HttpRequest.class);
    HttpHeaders headers = mock(HttpHeaders.class);
    HttpResponse response = mock(HttpResponse.class);
    when(request.getHeaders()).thenReturn(headers);
    when(factory.buildGetRequest(any(GenericUrl.class))).thenReturn(request);
    when(request.execute()).thenReturn(response);
    when(response.getContent()).thenReturn(ByteArrayInputStream.nullInputStream());
    ServiceConnection connection =
        new ServiceConnection(false, factory).withService(Service.PUBAPI, true);
    String serverUrl = connection.getServer().toString();
    assertThat(serverUrl).isEqualTo("https://pubapi.registry.test");
    connection.sendGetRequest("/path", ImmutableMap.of());
    verify(headers).set("canary", "true");
  }
}
