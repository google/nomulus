// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import google.registry.util.GoogleCredentialsBundle;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;

/** Dagger module that provides all {@link GoogleCredentials} used in the application. */
@Module
public abstract class CredentialModule {

  /**
   * Provides a {@link GoogleCredentialsBundle} backed by the application default credential from
   * the Google Cloud Runtime. This credential may be used to access GCP APIs that are NOT part of
   * the Google Workspace.
   *
   * <p>The credential returned by the Cloud Runtime depends on the runtime environment:
   *
   * <ul>
   *   <li>On App Engine, returns a scope-less {@code ComputeEngineCredentials} for
   *       PROJECT_ID@appspot.gserviceaccount.com
   *   <li>On Compute Engine, returns a scope-less {@code ComputeEngineCredentials} for
   *       PROJECT_NUMBER-compute@developer.gserviceaccount.com
   *   <li>On end user host, this returns the credential downloaded by gcloud. Please refer to <a
   *       href="https://cloud.google.com/sdk/gcloud/reference/auth/application-default/login">Cloud
   *       SDK documentation</a> for details.
   * </ul>
   */
  @ApplicationDefaultCredential
  @Provides
  @Singleton
  public static GoogleCredentialsBundle provideApplicationDefaultCredential() {
    GoogleCredentials credential;
    try {
      credential = GoogleCredentials.getApplicationDefault();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return GoogleCredentialsBundle.create(credential);
  }

  /**
   * Provides a {@link GoogleCredentialsBundle} for accessing Google Workspace APIs, such as Drive
   * and Sheets.
   */
  @GoogleWorkspaceCredential
  @Provides
  @Singleton
  public static GoogleCredentialsBundle provideGSuiteDriveCredential(
      @ApplicationDefaultCredential GoogleCredentialsBundle applicationDefaultCredential,
      @Config("defaultCredentialOauthScopes") ImmutableList<String> requiredScopes) {
    GoogleCredentials credential = applicationDefaultCredential.getGoogleCredentials();
    // Although credential is scope-less, its `createScopedRequired` method still returns false.
    credential = credential.createScoped(requiredScopes);
    return GoogleCredentialsBundle.create(credential);
  }

  /**
   * Provides a {@link GoogleCredentialsBundle} with delegated access to Google Workspace APIs for
   * the application default credential user.
   *
   * <p>The Workspace domain must grant delegated admin access to the default service account user
   * (project-id@appspot.gserviceaccount.com on AppEngine) with all scopes in {@code defaultScopes}
   * and {@code delegationScopes}.
   */
  @AdcDelegatedCredential
  @Provides
  @Singleton
  public static GoogleCredentialsBundle provideSelfSignedAdminDelegatedCredential(
      @Config("defaultCredentialOauthScopes") ImmutableList<String> defaultScopes,
      @Config("delegatedCredentialOauthScopes") ImmutableList<String> delegationScopes,
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("gSuiteAdminAccountEmailAddress") String gSuiteAdminAccountEmailAddress,
      @Config("tokenRefreshDelay") Duration tokenRefreshDelay,
      Clock clock) {
    return createSelfSignedDelegatedCredential(
        defaultScopes,
        delegationScopes,
        credentialsBundle,
        gSuiteAdminAccountEmailAddress,
        tokenRefreshDelay,
        clock);
  }

  /**
   * Provides a {@link GoogleCredentialsBundle} for sending emails through Google Workspace.
   *
   * <p>The Workspace domain must grant delegated admin access to the default service account user
   * (project-id@appspot.gserviceaccount.com on AppEngine) with all scopes in {@code defaultScopes}
   * and {@code delegationScopes}. In addition, the user {@code gSuiteOutgoingEmailAddress} must
   * have the permission to send emails.
   */
  @GmailDelegatedCredential
  @Provides
  @Singleton
  public static GoogleCredentialsBundle provideSelfSignedGmailDelegatedCredential(
      @Config("defaultCredentialOauthScopes") ImmutableList<String> defaultScopes,
      @Config("delegatedCredentialOauthScopes") ImmutableList<String> delegationScopes,
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("gSuiteNewOutgoingEmailAddress") String gSuiteOutgoingEmailAddress,
      @Config("tokenRefreshDelay") Duration tokenRefreshDelay,
      Clock clock) {
    return createSelfSignedDelegatedCredential(
        defaultScopes,
        delegationScopes,
        credentialsBundle,
        gSuiteOutgoingEmailAddress,
        tokenRefreshDelay,
        clock);
  }

  public static GoogleCredentialsBundle createSelfSignedDelegatedCredential(
      ImmutableList<String> defaultScopes,
      ImmutableList<String> delegationScopes,
      GoogleCredentialsBundle credentialsBundle,
      String gSuiteUserEmailAddress,
      Duration tokenRefreshDelay,
      Clock clock) {
    GoogleCredentials signer = credentialsBundle.getGoogleCredentials();

    checkArgument(
        signer instanceof ServiceAccountSigner,
        "Expecting a ServiceAccountSigner, found %s.",
        signer.getClass().getSimpleName());

    try {
      // Refreshing as sanity check on the ADC.
      signer.refresh();
    } catch (IOException e) {
      throw new RuntimeException("Cannot refresh the ApplicationDefaultCredential", e);
    }

    DelegatedCredentials credential =
        DelegatedCredentials.createSelfSignedDelegatedCredential(
            (ServiceAccountSigner) signer,
            ImmutableList.<String>builder().addAll(defaultScopes).addAll(delegationScopes).build(),
            gSuiteUserEmailAddress,
            clock,
            tokenRefreshDelay);
    return GoogleCredentialsBundle.create(credential);
  }

  /** Dagger qualifier for the scope-less Application Default Credential. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface ApplicationDefaultCredential {}

  /** Dagger qualifier for the credential for Google Workspace APIs. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface GoogleWorkspaceCredential {}

  /**
   * Dagger qualifier for a credential with delegated Email-sending permission for a dasher domain
   * (for Google Workspace) backed by the application default credential (ADC).
   */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface GmailDelegatedCredential {}

  /**
   * Dagger qualifier for a credential with delegated admin access for a dasher domain (for Google
   * Workspace) backed by the application default credential (ADC).
   */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface AdcDelegatedCredential {}

  /** Dagger qualifier for the local credential used in the nomulus tool. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface LocalCredential {}

  /** Dagger qualifier for the JSON string used to create the local credential. */
  @Qualifier
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  public @interface LocalCredentialJson {}
}
