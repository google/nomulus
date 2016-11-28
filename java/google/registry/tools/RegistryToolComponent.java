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

package google.registry.tools;

import dagger.Component;
import domains.donuts.keyring.DonutsKeyringModule;
import google.registry.config.ConfigModule;
import google.registry.dns.writer.VoidDnsWriterModule;
import google.registry.dns.writer.clouddns.CloudDnsWriterModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriterModule;
import google.registry.keyring.api.KeyModule;
import google.registry.request.Modules.DatastoreServiceModule;
import google.registry.request.Modules.Jackson2Module;
import google.registry.request.Modules.URLFetchServiceModule;
import google.registry.util.SystemClock.SystemClockModule;
import google.registry.util.SystemSleeper.SystemSleeperModule;

/**
 * Dagger component for Registry Tool.
 *
 * <p>Any command class with {@code @Inject} fields <i>must</i> be listed as a method here.
 * Otherwise {@link RegistryCli} will not be able to populate those fields after its instantiation.
 */
@Component(
  modules = {
    ConfigModule.class,
    DatastoreServiceModule.class,
    CloudDnsWriterModule.class,
    DnsUpdateWriterModule.class,
    DonutsKeyringModule.class,
    Jackson2Module.class,
    KeyModule.class,
    RegistryToolModule.class,
    SystemClockModule.class,
    SystemSleeperModule.class,
    URLFetchServiceModule.class,
    VoidDnsWriterModule.class,
  }
)
interface RegistryToolComponent {
  void inject(CreateAnchorTenantCommand command);
  void inject(CreateContactCommand command);
  void inject(CreateDomainCommand command);
  void inject(CreateLrpTokensCommand command);
  void inject(CreateTldCommand command);
  void inject(EncryptEscrowDepositCommand command);
  void inject(GenerateApplicationsReportCommand command);
  void inject(GenerateDnsReportCommand command);
  void inject(GenerateEscrowDepositCommand command);
  void inject(GhostrydeCommand command);
  void inject(ListCursorsCommand command);
  void inject(PendingEscrowCommand command);
  void inject(SendEscrowReportToIcannCommand command);
  void inject(SetupOteCommand command);
  void inject(UpdateCursorsCommand command);
  void inject(UpdateTldCommand command);
  void inject(ValidateEscrowDepositCommand command);
  void inject(WhoisQueryCommand command);
}
