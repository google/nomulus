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

package google.registry.tools;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig;

/**
 * Module for providing the HttpRequestFactory.
 *
 * <p>Localhost connections go to the App Engine dev server. The dev server differs from most HTTP
 * connections in that they don't require OAuth2 credentials, but instead require a special cookie.
 */
@Module
class RequestFactoryModule {
  
  static final int REQUEST_TIMEOUT_MS = 10 * 60 * 1000;

  @Provides
  static HttpRequestFactory provideHttpRequestFactory(
      @DefaultCredential GoogleCredential credential) {
    if (RegistryConfig.areServersLocal()) {
      return new NetHttpTransport()
          .createRequestFactory(
              request ->
                  request
                      .getHeaders()
                      .setCookie("dev_appserver_login=test@example.com:true:1858047912411"));
    } else {
      return new NetHttpTransport()
          .createRequestFactory(
              request -> {
                credential.initialize(request);
                // GAE request times out after 10 min, so here we set the timeout to 10 min. This is
                // needed to support some nomulus commands like updating premium lists that take
                // a lot of time to complete.
                // See https://developers.google.com/api-client-library/java/google-api-java-client/errors
                request.setConnectTimeout(REQUEST_TIMEOUT_MS);
                request.setReadTimeout(REQUEST_TIMEOUT_MS);
              });
    }
  }
}
