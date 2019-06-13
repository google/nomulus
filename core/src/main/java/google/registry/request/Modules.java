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

import static com.google.appengine.api.datastore.DatastoreServiceFactory.getDatastoreService;

import com.google.api.client.extensions.appengine.http.UrlFetchTransport;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

/** Dagger modules for App Engine services and other vendor classes. */
public final class Modules {

  /** Dagger module for {@link DatastoreService}. */
  @Module
  public static final class DatastoreServiceModule {
    private static final DatastoreService datastoreService = getDatastoreService();

    @Provides
    static DatastoreService provideDatastoreService() {
      return datastoreService;
    }
  }

  /** Dagger module for {@link URLFetchService}. */
  @Module
  public static final class URLFetchServiceModule {
    private static final URLFetchService fetchService = URLFetchServiceFactory.getURLFetchService();

    @Provides
    static URLFetchService provideURLFetchService() {
      return fetchService;
    }
  }

  /** Dagger module for {@link UserService}. */
  @Module
  public static final class UserServiceModule {
    private static final UserService userService = UserServiceFactory.getUserService();

    @Provides
    static UserService provideUserService() {
      return userService;
    }
  }

  /**
   * Dagger module that causes the Jackson2 JSON parser to be used for Google APIs requests.
   *
   * <p>Jackson1 and GSON can also satisfy the {@link JsonFactory} interface, but we've decided to
   * go with Jackson2, since it's what's used in the public examples for using Google APIs.
   */
  @Module
  public static final class Jackson2Module {
    @Provides
    static JsonFactory provideJsonFactory() {
      return JacksonFactory.getDefaultInstance();
    }
  }

  /** Dagger module that causes the App Engine's URL fetcher to be used for Google APIs requests. */
  @Module
  public static final class UrlFetchTransportModule {
    private static final UrlFetchTransport HTTP_TRANSPORT = new UrlFetchTransport();

    @Provides
    static HttpTransport provideHttpTransport() {
      return HTTP_TRANSPORT;
    }
  }

  /**
   * Dagger module that provides standard {@link NetHttpTransport}. Used in non App Engine
   * environment.
   */
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
