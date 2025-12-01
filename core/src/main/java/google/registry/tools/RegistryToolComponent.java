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

import dagger.BindsInstance;
import dagger.Component;
import dagger.Lazy;
import google.registry.batch.BatchModule;
import google.registry.bigquery.BigqueryModule;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule.LocalCredentialJson;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.writer.DnsWritersModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.model.ModelModule;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceModule.NomulusToolJpaTm;
import google.registry.persistence.PersistenceModule.ReadOnlyReplicaJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.rde.RdeModule;
import google.registry.request.Modules.GsonModule;
import google.registry.request.Modules.UrlConnectionServiceModule;
import google.registry.tools.AuthModule.LocalCredentialModule;
import google.registry.util.UtilsModule;
import jakarta.inject.Singleton;
import javax.annotation.Nullable;

/**
 * Dagger component for Registry Tool.
 *
 * <p>Any command class with {@code @Inject} fields <i>must</i> be listed as a method here.
 * Otherwise {@link RegistryCli} will not be able to populate those fields after its instantiation.
 */
@Singleton
@Component(
    modules = {
      AuthModule.class,
      BatchModule.class,
      BigqueryModule.class,
      ConfigModule.class,
      CloudTasksUtilsModule.class,
      DnsWritersModule.class,
      GsonModule.class,
      KeyModule.class,
      KeyringModule.class,
      LocalCredentialModule.class,
      ModelModule.class,
      PersistenceModule.class,
      RdeModule.class,
      RegistryToolDataflowModule.class,
      RequestFactoryModule.class,
      SecretManagerModule.class,
      UrlConnectionServiceModule.class,
      UtilsModule.class
    })
interface RegistryToolComponent {
  void inject(AckPollMessagesCommand command);

  void inject(CheckDomainClaimsCommand command);

  void inject(CheckDomainCommand command);

  void inject(ConfigureTldCommand command);

  void inject(CountDomainsCommand command);

  void inject(CreateAnchorTenantCommand command);

  void inject(CreateCdnsTld command);

  void inject(CreateContactCommand command);

  void inject(CreateDomainCommand command);

  void inject(CreateRegistrarCommand command);

  void inject(CreateUserCommand command);

  void inject(CurlCommand command);

  void inject(DeleteUserCommand command);

  void inject(EncryptEscrowDepositCommand command);

  void inject(EnqueuePollMessageCommand command);

  void inject(GenerateAllocationTokensCommand command);

  void inject(GenerateDnsReportCommand command);

  void inject(GenerateEscrowDepositCommand command);

  void inject(GetBulkPricingPackageCommand command);

  void inject(GetContactCommand command);

  void inject(GetDomainCommand command);

  void inject(GetFeatureFlagCommand command);

  void inject(GetHostCommand command);

  void inject(GetKeyringSecretCommand command);

  void inject(GetSqlCredentialCommand command);

  void inject(GetTldCommand command);

  void inject(GhostrydeCommand command);

  void inject(ListCursorsCommand command);

  void inject(ListFeatureFlagsCommand command);

  void inject(LockDomainCommand command);

  void inject(LoginCommand command);

  void inject(LogoutCommand command);

  void inject(PendingEscrowCommand command);

  void inject(RdapQueryCommand command);

  void inject(RenewDomainCommand command);

  void inject(SaveSqlCredentialCommand command);

  void inject(SendEscrowReportToIcannCommand command);

  void inject(SetupOteCommand command);

  void inject(UniformRapidSuspensionCommand command);

  void inject(UnlockDomainCommand command);

  void inject(UnrenewDomainCommand command);

  void inject(UpdateCursorsCommand command);

  void inject(UpdateDomainCommand command);

  void inject(UpdateKeyringSecretCommand command);

  void inject(UpdateRegistrarCommand command);

  void inject(ValidateEscrowDepositCommand command);

  void inject(ValidateLoginCredentialsCommand command);

  ServiceConnection serviceConnection();

  @LocalCredentialJson
  String googleCredentialJson();

  @NomulusToolJpaTm
  Lazy<JpaTransactionManager> nomulusToolJpaTransactionManager();

  @ReadOnlyReplicaJpaTm
  Lazy<JpaTransactionManager> nomulusToolReplicaJpaTransactionManager();

  @Component.Builder
  interface Builder {
    @BindsInstance
    Builder credentialFilePath(@Nullable @Config("credentialFilePath") String credentialFilePath);

    @BindsInstance
    Builder sqlAccessInfoFile(@Nullable @Config("sqlAccessInfoFile") String sqlAccessInfoFile);

    @BindsInstance
    Builder useCanary(@Config("useCanary") boolean useCanary);

    RegistryToolComponent build();
  }
}
