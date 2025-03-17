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

package google.registry.module.pubapi;

import dagger.Module;
import dagger.Subcomponent;
import google.registry.dns.DnsModule;
import google.registry.flows.CheckApiAction;
import google.registry.flows.CheckApiAction.CheckApiModule;
import google.registry.flows.TlsCredentials.EppTlsModule;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.rdap.RdapAutnumAction;
import google.registry.rdap.RdapDomainAction;
import google.registry.rdap.RdapDomainSearchAction;
import google.registry.rdap.RdapEmptyAction;
import google.registry.rdap.RdapEntityAction;
import google.registry.rdap.RdapEntitySearchAction;
import google.registry.rdap.RdapHelpAction;
import google.registry.rdap.RdapIpAction;
import google.registry.rdap.RdapModule;
import google.registry.rdap.RdapNameserverAction;
import google.registry.rdap.RdapNameserverSearchAction;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.whois.WhoisAction;
import google.registry.whois.WhoisHttpAction;
import google.registry.whois.WhoisModule;

/** Dagger component with per-request lifetime for "pubapi" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
      CheckApiModule.class,
      DnsModule.class,
      EppTlsModule.class,
      RdapModule.class,
      RequestModule.class,
      WhiteboxModule.class,
      WhoisModule.class,
    })
public interface PubApiRequestComponent {
  CheckApiAction checkApiAction();
  RdapAutnumAction rdapAutnumAction();
  RdapDomainAction rdapDomainAction();
  RdapDomainSearchAction rdapDomainSearchAction();

  RdapEmptyAction rdapEmptyAction();

  RdapEntityAction rdapEntityAction();
  RdapEntitySearchAction rdapEntitySearchAction();
  RdapHelpAction rdapHelpAction();
  RdapIpAction rdapDefaultAction();
  RdapNameserverAction rdapNameserverAction();
  RdapNameserverSearchAction rdapNameserverSearchAction();
  WhoisHttpAction whoisHttpAction();
  WhoisAction whoisAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<PubApiRequestComponent> {
    @Override public abstract Builder requestModule(RequestModule requestModule);
    @Override public abstract PubApiRequestComponent build();
  }

  @Module(subcomponents = PubApiRequestComponent.class)
  class PubApiRequestComponentModule {}
}
