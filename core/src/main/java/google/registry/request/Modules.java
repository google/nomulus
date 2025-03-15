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

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** Dagger modules for App Engine services and other vendor classes. */
public final class Modules {

  /** Dagger module for {@link UrlConnectionService}. */
  @Module
  public static final class UrlConnectionServiceModule {
    @Provides
    static UrlConnectionService provideUrlConnectionService() {
      return url -> {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection) {
          SSLContext tls13Context = SSLContext.getInstance("TLSv1.3");
          tls13Context.init(null, null, null);
          httpsConnection.setSSLSocketFactory(tls13Context.getSocketFactory());
        }
        return connection;
      };
    }
  }

  /** Dagger module that causes the Google GSON parser to be used for Google APIs requests. */
  @Module
  public static final class GsonModule {
    @Provides
    static JsonFactory provideJsonFactory() {
      return GsonFactory.getDefaultInstance();
    }
  }

  /** Dagger module that provides standard {@link NetHttpTransport}. */
  @Module
  public static final class NetHttpTransportModule {

    @Provides
    @Singleton
    static NetHttpTransport provideNetHttpTransport() {
      try {
        return GoogleNetHttpTransport.newTrustedTransport();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
