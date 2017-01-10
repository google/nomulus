// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.extensions.appengine.http.UrlFetchTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.modules.ModulesService;
import com.google.appengine.api.modules.ModulesServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.monitoring.whitebox.BigQueryMetricsEnqueuer;

import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

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

  /** Dagger module for {@link ModulesService}. */
  @Module
  public static final class ModulesServiceModule {
    private static final ModulesService modulesService = ModulesServiceFactory.getModulesService();

    @Provides
    static ModulesService provideModulesService() {
      return modulesService;
    }
  }

  /** Dagger module for {@link BigQueryMetricsEnqueuer} queue */
  @Module
  public static final class BigQueryStreamingMetricsModule {
    @Provides
    @Named(BigQueryMetricsEnqueuer.QUEUE)
    static Queue provideBigQueryStreamingMetricsQueue() {
      return getQueue(BigQueryMetricsEnqueuer.QUEUE);
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
   * Dagger module providing {@link AppIdentityCredential}.
   *
   * <p>This can be used to authenticate to Google APIs using the identity of your GAE app.
   *
   * @see UseAppIdentityCredentialForGoogleApisModule
   */
  @Module
  public static final class AppIdentityCredentialModule {
    @Provides
    static Function<Set<String>, AppIdentityCredential> provideAppIdentityCredential() {
      return new Function<Set<String>, AppIdentityCredential>() {
        @Override
        public AppIdentityCredential apply(Set<String> scopes) {
          return new AppIdentityCredential(scopes);
        }
      };
    }
  }

  /**
   * Dagger module causing Google APIs requests to be authorized with your GAE app identity.
   *
   * <p>You must also use the {@link AppIdentityCredentialModule}.
   */
  @Module
  public abstract static class UseAppIdentityCredentialForGoogleApisModule {
    @Binds
    abstract Function<Set<String>, ? extends HttpRequestInitializer>
        provideHttpRequestInitializer(Function<Set<String>, AppIdentityCredential> credential);
  }

  /**
   * Module indicating Google API requests should be authorized with JSON {@link GoogleCredential}.
   *
   * <p>This is useful when configuring a component that runs the registry outside of the App Engine
   * environment, for example, in a command line environment.
   *
   * <p>You must also use the {@link GoogleCredentialModule}.
   */
  @Module
  public abstract static class UseGoogleCredentialForGoogleApisModule {
    @Binds
    abstract Function<Set<String>, ? extends HttpRequestInitializer>
        provideHttpRequestInitializer(Function<Set<String>, GoogleCredential> credential);
  }

  /**
   * Dagger module providing {@link GoogleCredential} from a JSON key file contents.
   *
   * <p>This satisfies the {@link HttpRequestInitializer} interface for authenticating Google APIs
   * requests, just like {@link AppIdentityCredential}.
   *
   * <p>But we consider GAE authentication more desirable and easier to manage operations-wise. So
   * this authentication method should only be used for the following situations:
   *
   * <ol>
   * <li>Locally-running programs (which aren't executing on the App Engine platform)
   * <li>Spreadsheet service (which can't use {@link AppIdentityCredential} due to an old library)
   * </ol>
   *
   * @see google.registry.keyring.api.Keyring#getJsonCredential()
   */
  @Module
  public static final class GoogleCredentialModule {

    @Provides
    @Singleton
    static GoogleCredential provideGoogleCredential(
        HttpTransport httpTransport,
        JsonFactory jsonFactory,
        @Key("jsonCredential") String jsonCredential) {
      try {
        return GoogleCredential.fromStream(
            new ByteArrayInputStream(jsonCredential.getBytes(UTF_8)), httpTransport, jsonFactory);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Provides
    static Function<Set<String>, GoogleCredential> provideScopedGoogleCredential(
        final Provider<GoogleCredential> googleCredentialProvider) {
      return new Function<Set<String>, GoogleCredential>() {
        @Override
        public GoogleCredential apply(Set<String> scopes) {
          return googleCredentialProvider.get().createScoped(scopes);
        }
      };
    }

    /**
     * Provides a GoogleCredential that will connect to GAE using delegated admin access.  This is
     * needed for API calls requiring domain admin access to the relevant GAFYD using delegated
     * scopes, e.g. the Directory API and the Groupssettings API.
     *
     * <p>Note that you must call {@link GoogleCredential#createScoped} on the credential provided
     * by this method first before using it, as this does not and cannot set the scopes, and a
     * credential without scopes doesn't actually provide access to do anything.
     */
    @Provides
    @Singleton
    @Named("delegatedAdmin")
    static GoogleCredential provideDelegatedAdminGoogleCredential(
        GoogleCredential googleCredential,
        HttpTransport httpTransport,
        @Config("googleAppsAdminEmailAddress") String googleAppsAdminEmailAddress) {
      return new GoogleCredential.Builder()
          .setTransport(httpTransport)
          .setJsonFactory(googleCredential.getJsonFactory())
          .setServiceAccountId(googleCredential.getServiceAccountId())
          .setServiceAccountPrivateKey(googleCredential.getServiceAccountPrivateKey())
          // Set the scopes to empty because the default value is null, which throws an NPE in the
          // GoogleCredential constructor.  We don't yet know the actual scopes to use here, and it
          // is thus the responsibility of every user of a delegated admin credential to call
          // createScoped() on it first to get the version with the correct scopes set.
          .setServiceAccountScopes(ImmutableSet.<String>of())
          .setServiceAccountUser(googleAppsAdminEmailAddress)
          .build();
    }
  }
}
