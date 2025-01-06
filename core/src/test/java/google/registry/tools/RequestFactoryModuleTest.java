// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.tools.RequestFactoryModule.REQUEST_TIMEOUT_MS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.GenericData;
import com.google.auth.oauth2.UserCredentials;
import google.registry.config.RegistryConfig;
import google.registry.testing.SystemPropertyExtension;
import google.registry.util.GoogleCredentialsBundle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RequestFactoryModule}. */
@ExtendWith(MockitoExtension.class)
public class RequestFactoryModuleTest {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @Mock public GoogleCredentialsBundle credentialsBundle;

  @BeforeEach
  void beforeEach() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
  }

  @Test
  void test_provideHttpRequestFactory_localhost() throws Exception {
    // Make sure that localhost creates a request factory with an initializer.
    boolean origIsLocal = RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal;
    RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal = true;
    try {
      HttpRequestFactory factory =
          RequestFactoryModule.provideHttpRequestFactory(credentialsBundle, "client-id");
      HttpRequestInitializer initializer = factory.getInitializer();
      assertThat(initializer).isNotNull();
      HttpRequest request = factory.buildGetRequest(new GenericUrl("http://localhost"));
      initializer.initialize(request);
    } finally {
      RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal = origIsLocal;
    }
  }

  @Test
  void test_provideHttpRequestFactory_remote() throws Exception {
    // Mock the request/response to/from the OIDC server requesting an ID token
    UserCredentials mockUserCredentials = mock(UserCredentials.class);
    when(credentialsBundle.getGoogleCredentials()).thenReturn(mockUserCredentials);
    HttpTransport mockTransport = mock(HttpTransport.class);
    when(credentialsBundle.getHttpTransport()).thenReturn(mockTransport);
    when(credentialsBundle.getJsonFactory()).thenReturn(GsonFactory.getDefaultInstance());
    HttpRequestFactory mockRequestFactory = mock(HttpRequestFactory.class);
    when(mockTransport.createRequestFactory()).thenReturn(mockRequestFactory);
    HttpRequest mockPostRequest = mock(HttpRequest.class);
    when(mockRequestFactory.buildPostRequest(any(), any())).thenReturn(mockPostRequest);
    HttpResponse mockResponse = mock(HttpResponse.class);
    when(mockPostRequest.execute()).thenReturn(mockResponse);
    GenericData genericDataResponse = new GenericData();
    genericDataResponse.set("id_token", "oidc.token");
    when(mockResponse.parseAs(GenericData.class)).thenReturn(genericDataResponse);

    boolean origIsLocal = RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal;
    RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal = false;
    try {
      HttpRequestFactory factory =
          RequestFactoryModule.provideHttpRequestFactory(credentialsBundle, "clientId");
      HttpRequest request = factory.buildGetRequest(new GenericUrl("http://localhost"));
      @SuppressWarnings("unchecked")
      List<String> authHeaders = (List<String>) request.getHeaders().get("Authorization");
      assertThat(authHeaders.size()).isEqualTo(1);
      assertThat(authHeaders.get(0)).isEqualTo("Bearer oidc.token");
      assertThat(request.getConnectTimeout()).isEqualTo(REQUEST_TIMEOUT_MS);
      assertThat(request.getReadTimeout()).isEqualTo(REQUEST_TIMEOUT_MS);
    } finally {
      RegistryConfig.CONFIG_SETTINGS.get().gcpProject.isLocal = origIsLocal;
    }
  }
}
