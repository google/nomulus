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
import google.registry.dns.writer.VoidDnsWriterModule;
import google.registry.dns.writer.clouddns.CloudDnsWriterModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriterModule;
import google.registry.export.datastore.DatastoreAdminModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.DummyKeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.keyring.kms.KmsModule;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceModule.NomulusToolJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.rde.RdeModule;
import google.registry.request.Modules.DatastoreServiceModule;
import google.registry.request.Modules.Jackson2Module;
import google.registry.request.Modules.URLFetchServiceModule;
import google.registry.request.Modules.UrlFetchTransportModule;
import google.registry.request.Modules.UserServiceModule;
import google.registry.tools.AuthModule.LocalCredentialModule;
import google.registry.tools.javascrap.BackfillRegistryLocksCommand;
import google.registry.tools.javascrap.CompareEscrowDepositsCommand;
import google.registry.tools.javascrap.DeleteContactByRoidCommand;
import google.registry.tools.javascrap.HardDeleteHostCommand;
import google.registry.util.UtilsModule;
import google.registry.whois.NonCachingWhoisModule;
import javax.annotation.Nullable;
import javax.inject.Singleton;

/**
 * Dagger component for Registry Tool.
 *
 * <p>Any command class with {@code @Inject} fields <i>must</i> be listed as a method here.
 * Otherwise {@link RegistryCli} will not be able to populate those fields after its instantiation.
 */
@Singleton
@Component(
    modules = {
      AppEngineAdminApiModule.class,
      AuthModule.class,
      BatchModule.class,
      BigqueryModule.class,
      ConfigModule.class,
      CloudDnsWriterModule.class,
      CloudTasksUtilsModule.class,
      DatastoreAdminModule.class,
      DatastoreServiceModule.class,
      DummyKeyringModule.class,
      DnsUpdateWriterModule.class,
      Jackson2Module.class,
      KeyModule.class,
      KeyringModule.class,
      KmsModule.class,
      LocalCredentialModule.class,
      PersistenceModule.class,
      RdeModule.class,
      RequestFactoryModule.class,
      SecretManagerModule.class,
      URLFetchServiceModule.class,
      UrlFetchTransportModule.class,
      UserServiceModule.class,
      UtilsModule.class,
      VoidDnsWriterModule.class,
      NonCachingWhoisModule.class
    })
interface RegistryToolComponent {
  void inject(AckPollMessagesCommand command);

  void inject(BackfillRegistryLocksCommand command);

  void inject(CheckDomainClaimsCommand command);

  void inject(CheckDomainCommand command);

  void inject(CompareEscrowDepositsCommand command);

  void inject(CountDomainsCommand command);

  void inject(CreateAnchorTenantCommand command);

  void inject(CreateCdnsTld command);

  void inject(CreateContactCommand command);

  void inject(CreateDomainCommand command);

  void inject(CreateRegistrarCommand command);

  void inject(CreateTldCommand command);

  void inject(DeleteContactByRoidCommand command);

  void inject(EncryptEscrowDepositCommand command);

  void inject(EnqueuePollMessageCommand command);

  void inject(GenerateAllocationTokensCommand command);

  void inject(GenerateDnsReportCommand command);

  void inject(GenerateEscrowDepositCommand command);

  void inject(GetKeyringSecretCommand command);

  void inject(GetOperationStatusCommand command);

  void inject(GetSqlCredentialCommand command);

  void inject(GhostrydeCommand command);

  void inject(HardDeleteHostCommand command);

  void inject(ImportDatastoreCommand command);

  void inject(ListCursorsCommand command);

  void inject(ListDatastoreOperationsCommand command);

  void inject(LoadSnapshotCommand command);

  void inject(LockDomainCommand command);

  void inject(LoginCommand command);

  void inject(LogoutCommand command);

  void inject(PendingEscrowCommand command);

  void inject(RenewDomainCommand command);

  void inject(SaveSqlCredentialCommand command);

  void inject(SendEscrowReportToIcannCommand command);

  void inject(SetNumInstancesCommand command);

  void inject(SetSqlReplayCheckpointCommand command);

  void inject(SetupOteCommand command);

  void inject(UnlockDomainCommand command);

  void inject(UnrenewDomainCommand command);

  void inject(UpdateCursorsCommand command);

  void inject(UpdateDomainCommand command);

  void inject(UpdateKeyringSecretCommand command);

  void inject(UpdateRegistrarCommand command);

  void inject(UpdateTldCommand command);

  void inject(ValidateEscrowDepositCommand command);

  void inject(ValidateLoginCredentialsCommand command);

  void inject(WhoisQueryCommand command);

  AppEngineConnection appEngineConnection();

  @LocalCredentialJson
  String googleCredentialJson();

  @NomulusToolJpaTm
  Lazy<JpaTransactionManager> nomulusToolJpaTransactionManager();

  @Component.Builder
  interface Builder {
    @BindsInstance
    Builder credentialFilePath(@Nullable @Config("credentialFilePath") String credentialFilePath);

    @BindsInstance
    Builder sqlAccessInfoFile(@Nullable @Config("sqlAccessInfoFile") String sqlAccessInfoFile);

    RegistryToolComponent build();
  }
}
