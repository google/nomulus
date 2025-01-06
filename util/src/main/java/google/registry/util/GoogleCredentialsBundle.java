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

package google.registry.util;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.Serializable;

/**
 * Helper class to provide {@link HttpTransport}, {@link JsonFactory} and {@link
 * HttpRequestInitializer} for a given {@link GoogleCredentials}. These classes are normally needed
 * for creating the instance of a GCP client.
 */
public class GoogleCredentialsBundle implements Serializable {

  private static final long serialVersionUID = -7184513645423688942L;
  private static final HttpTransport HTTP_TRANSPORT = Utils.getDefaultTransport();
  private static final JsonFactory JSON_FACTORY = Utils.getDefaultJsonFactory();

  private final GoogleCredentials googleCredentials;

  protected GoogleCredentialsBundle(GoogleCredentials googleCredentials) {
    checkNotNull(googleCredentials);
    this.googleCredentials = googleCredentials;
  }

  /** Creates a {@link GoogleCredentialsBundle} instance from given {@link GoogleCredentials}. */
  public static GoogleCredentialsBundle create(GoogleCredentials credentials) {
    return new GoogleCredentialsBundle(credentials);
  }

  /**
   * Returns the service account email address of the underlying {@link GoogleCredentials}, if
   * possible.
   */
  public String serviceAccount() {
    if (googleCredentials instanceof ServiceAccountSigner) {
      return ((ServiceAccountSigner) googleCredentials).getAccount();
    } else {
      throw new RuntimeException(
          String.format(
              "%s is a %s, not a service account.",
              googleCredentials, googleCredentials.getClass().getSimpleName()));
    }
  }

  /** Returns the same {@link GoogleCredentials} used to create this object. */
  public GoogleCredentials getGoogleCredentials() {
    return googleCredentials;
  }

  /** Returns the instance of {@link HttpTransport}. */
  public HttpTransport getHttpTransport() {
    return HTTP_TRANSPORT;
  }

  /** Returns the instance of {@link JsonFactory}. */
  public JsonFactory getJsonFactory() {
    return JSON_FACTORY;
  }

  /** Returns the instance of {@link HttpRequestInitializer}. */
  public HttpRequestInitializer getHttpRequestInitializer() {
    return new HttpCredentialsAdapter(googleCredentials);
  }
}
