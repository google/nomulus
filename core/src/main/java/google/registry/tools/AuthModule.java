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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.Details;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.CredentialModule.LocalCredential;
import google.registry.config.CredentialModule.LocalCredentialJson;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** Module providing the dependency graph for authorization credentials. */
@Module
public class AuthModule {

  private static final File DATA_STORE_DIR =
      new File(System.getProperty("user.home"), ".config/nomulus/credentials");

  @Provides
  @StoredCredential
  static Credential provideCredential(
      GoogleAuthorizationCodeFlow flow, @ClientScopeQualifier String clientScopeQualifier) {
    try {
      // Try to load the credentials, throw an exception if we fail.
      Credential credential = flow.loadCredential(clientScopeQualifier);
      if (credential == null) {
        throw new LoginRequiredException();
      }
      return credential;
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Provides
  @LocalCredential
  public static GoogleCredentialsBundle provideLocalCredential(
      @LocalCredentialJson String credentialJson,
      @Config("localCredentialOauthScopes") ImmutableList<String> scopes) {
    try {
      GoogleCredentials credential =
          GoogleCredentials.fromStream(new ByteArrayInputStream(credentialJson.getBytes(UTF_8)));
      if (credential.createScopedRequired()) {
        credential = credential.createScoped(scopes);
      }
      return GoogleCredentialsBundle.create(credential);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // TODO(b/138195359): Deprecate this credential once Cloud SQL socket library uses the new auth
  // library.
  @Provides
  @CloudSqlClientCredential
  public static Credential providesLocalCredentialForCloudSqlClient(
      @LocalCredentialJson String credentialJson,
      @Config("localCredentialOauthScopes") ImmutableList<String> credentialScopes) {
    try {
      GoogleCredential credential =
          GoogleCredential.fromStream(new ByteArrayInputStream(credentialJson.getBytes(UTF_8)));
      if (credential.createScopedRequired()) {
        credential = credential.createScoped(credentialScopes);
      }
      return credential;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Error occurred while creating a GoogleCredential for Cloud SQL client", e);
    }
  }

  @Provides
  public static GoogleAuthorizationCodeFlow provideAuthorizationCodeFlow(
      JsonFactory jsonFactory,
      GoogleClientSecrets clientSecrets,
      @Config("localCredentialOauthScopes") ImmutableList<String> requiredOauthScopes,
      AbstractDataStoreFactory dataStoreFactory) {
    try {
      return new GoogleAuthorizationCodeFlow.Builder(
          new NetHttpTransport(), jsonFactory, clientSecrets, requiredOauthScopes)
          .setDataStoreFactory(dataStoreFactory)
          .build();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Provides
  static Details provideDefaultInstalledDetails() {
    return new Details()
        .setAuthUri("https://accounts.google.com/o/oauth2/auth")
        .setTokenUri("https://accounts.google.com/o/oauth2/token");
  }

  @Provides
  public static GoogleClientSecrets provideClientSecrets(
      @Config("toolsClientId") String clientId,
      @Config("toolsClientSecret") String clientSecret,
      Details details) {
    return new GoogleClientSecrets()
        .setInstalled(details.setClientId(clientId).setClientSecret(clientSecret));
  }

  @Provides
  @LocalCredentialJson
  public static String provideLocalCredentialJson(
      Lazy<GoogleClientSecrets> clientSecrets,
      @StoredCredential Lazy<Credential> credential,
      @Nullable @Config("credentialFilePath") String credentialFilePath) {
    try {
      if (credentialFilePath != null) {
        return new String(Files.readAllBytes(Paths.get(credentialFilePath)), UTF_8);
      } else {
        return new Gson()
            .toJson(
                ImmutableMap.<String, String>builder()
                    .put("type", "authorized_user")
                    .put("client_id", clientSecrets.get().getDetails().getClientId())
                    .put("client_secret", clientSecrets.get().getDetails().getClientSecret())
                    .put("refresh_token", credential.get().getRefreshToken())
                    .build());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @OAuthClientId
  static String provideClientId(GoogleClientSecrets clientSecrets) {
    return clientSecrets.getDetails().getClientId();
  }

  @Provides
  @ClientScopeQualifier
  static String provideClientScopeQualifier(
      @OAuthClientId String clientId,
      @Config("localCredentialOauthScopes") ImmutableList<String> scopes) {
    return clientId + " " + Joiner.on(" ").join(Ordering.natural().sortedCopy(scopes));
  }

  @Provides
  @Singleton
  public static AbstractDataStoreFactory provideDataStoreFactory() {
    try {
      return new FileDataStoreFactory(DATA_STORE_DIR);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Dagger qualifier for the {@link Credential} constructed from the data stored on disk.
   *
   * <p>This {@link Credential} should not be used in another module, hence the private qualifier.
   * It's only use is to build a {@link GoogleCredentials}, which is used in injection sites
   * elsewhere.
   */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  private @interface StoredCredential {}

  /** Dagger qualifier for {@link Credential} used by the Cloud SQL client in the nomulus tool. */
  @Qualifier
  @Documented
  public @interface CloudSqlClientCredential {}

  /** Dagger qualifier for the credential qualifier consisting of client and scopes. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @interface ClientScopeQualifier {}

  /** Dagger qualifier for the OAuth2 client id. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @interface OAuthClientId {}

  @Module
  abstract static class LocalCredentialModule {
    @Binds
    @DefaultCredential
    abstract GoogleCredentialsBundle provideLocalCredentialAsDefaultCredential(
        @LocalCredential GoogleCredentialsBundle credential);
  }

  /** Raised when we need a user login. */
  static class LoginRequiredException extends RuntimeException {
    LoginRequiredException() {}
  }
}
