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

package google.registry.flows;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;
import google.registry.batch.BatchModule;
import google.registry.dns.DnsModule;
import google.registry.flows.contact.ContactCheckFlow;
import google.registry.flows.contact.ContactCreateFlow;
import google.registry.flows.contact.ContactDeleteFlow;
import google.registry.flows.contact.ContactInfoFlow;
import google.registry.flows.contact.ContactTransferApproveFlow;
import google.registry.flows.contact.ContactTransferCancelFlow;
import google.registry.flows.contact.ContactTransferQueryFlow;
import google.registry.flows.contact.ContactTransferRejectFlow;
import google.registry.flows.contact.ContactTransferRequestFlow;
import google.registry.flows.contact.ContactUpdateFlow;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.flows.domain.DomainCheckFlow;
import google.registry.flows.domain.DomainClaimsCheckFlow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.flows.domain.DomainDeleteFlow;
import google.registry.flows.domain.DomainInfoFlow;
import google.registry.flows.domain.DomainRenewFlow;
import google.registry.flows.domain.DomainRestoreRequestFlow;
import google.registry.flows.domain.DomainTransferApproveFlow;
import google.registry.flows.domain.DomainTransferCancelFlow;
import google.registry.flows.domain.DomainTransferQueryFlow;
import google.registry.flows.domain.DomainTransferRejectFlow;
import google.registry.flows.domain.DomainTransferRequestFlow;
import google.registry.flows.domain.DomainUpdateFlow;
import google.registry.flows.host.HostCheckFlow;
import google.registry.flows.host.HostCreateFlow;
import google.registry.flows.host.HostDeleteFlow;
import google.registry.flows.host.HostInfoFlow;
import google.registry.flows.host.HostUpdateFlow;
import google.registry.flows.poll.PollAckFlow;
import google.registry.flows.poll.PollRequestFlow;
import google.registry.flows.session.HelloFlow;
import google.registry.flows.session.LoginFlow;
import google.registry.flows.session.LogoutFlow;
import google.registry.model.eppcommon.Trid;

/** Dagger component for flow classes. */
@FlowScope
@Subcomponent(modules = {
    BatchModule.class,
    CustomLogicModule.class,
    DnsModule.class,
    FlowModule.class,
    FlowComponent.FlowComponentModule.class})
public interface FlowComponent {

  Trid trid();
  FlowRunner flowRunner();

  // Flows must be added here and in FlowComponentModule below.
  ContactCheckFlow contactCheckFlow();
  ContactCreateFlow contactCreateFlow();
  ContactDeleteFlow contactDeleteFlow();
  ContactInfoFlow contactInfoFlow();
  ContactTransferApproveFlow contactTransferApproveFlow();
  ContactTransferCancelFlow contactTransferCancelFlow();
  ContactTransferQueryFlow contactTransferQueryFlow();
  ContactTransferRejectFlow contactTransferRejectFlow();
  ContactTransferRequestFlow contactTransferRequestFlow();
  ContactUpdateFlow contactUpdateFlow();
  DomainCheckFlow domainCheckFlow();
  DomainClaimsCheckFlow domainClaimsCheckFlow();
  DomainCreateFlow domainCreateFlow();
  DomainDeleteFlow domainDeleteFlow();
  DomainInfoFlow domainInfoFlow();
  DomainRenewFlow domainRenewFlow();
  DomainRestoreRequestFlow domainRestoreRequestFlow();
  DomainTransferApproveFlow domainTransferApproveFlow();
  DomainTransferCancelFlow domainTransferCancelFlow();
  DomainTransferQueryFlow domainTransferQueryFlow();
  DomainTransferRejectFlow domainTransferRejectFlow();
  DomainTransferRequestFlow domainTransferRequestFlow();
  DomainUpdateFlow domainUpdateFlow();
  HostCheckFlow hostCheckFlow();
  HostCreateFlow hostCreateFlow();
  HostDeleteFlow hostDeleteFlow();
  HostInfoFlow hostInfoFlow();
  HostUpdateFlow hostUpdateFlow();
  PollAckFlow pollAckFlow();
  PollRequestFlow pollRequestFlow();
  HelloFlow helloFlow();
  LoginFlow loginFlow();
  LogoutFlow logoutFlow();

  /** Dagger-implemented builder for this subcomponent. */
  @Subcomponent.Builder
  interface Builder {
    Builder flowModule(FlowModule flowModule);
    FlowComponent build();
  }

  /** Module to delegate injection of a desired {@link Flow}. */
  @Module
  class FlowComponentModule {
    // WARNING: @FlowScope is intentionally omitted here so that we get a fresh Flow instance on
    // each call to Provider<Flow>.get(), to avoid Flow instance re-use upon transaction retries.
    // TODO(b/29874464): fix this in a cleaner way.
    @Provides
    static Flow provideFlow(FlowComponent flows, Class<? extends Flow> clazz) {
      return clazz.equals(ContactCheckFlow.class) ? flows.contactCheckFlow()
          : clazz.equals(ContactCreateFlow.class) ? flows.contactCreateFlow()
          : clazz.equals(ContactDeleteFlow.class) ? flows.contactDeleteFlow()
          : clazz.equals(ContactInfoFlow.class) ? flows.contactInfoFlow()
          : clazz.equals(ContactTransferApproveFlow.class) ? flows.contactTransferApproveFlow()
          : clazz.equals(ContactTransferCancelFlow.class) ? flows.contactTransferCancelFlow()
          : clazz.equals(ContactTransferQueryFlow.class) ? flows.contactTransferQueryFlow()
          : clazz.equals(ContactTransferRejectFlow.class) ? flows.contactTransferRejectFlow()
          : clazz.equals(ContactTransferRequestFlow.class) ? flows.contactTransferRequestFlow()
          : clazz.equals(ContactUpdateFlow.class) ? flows.contactUpdateFlow()
          : clazz.equals(DomainCheckFlow.class) ? flows.domainCheckFlow()
          : clazz.equals(DomainClaimsCheckFlow.class) ? flows.domainClaimsCheckFlow()
          : clazz.equals(DomainCreateFlow.class) ? flows.domainCreateFlow()
          : clazz.equals(DomainDeleteFlow.class) ? flows.domainDeleteFlow()
          : clazz.equals(DomainInfoFlow.class) ? flows.domainInfoFlow()
          : clazz.equals(DomainRenewFlow.class) ? flows.domainRenewFlow()
          : clazz.equals(DomainRestoreRequestFlow.class) ? flows.domainRestoreRequestFlow()
          : clazz.equals(DomainTransferApproveFlow.class) ? flows.domainTransferApproveFlow()
          : clazz.equals(DomainTransferCancelFlow.class) ? flows.domainTransferCancelFlow()
          : clazz.equals(DomainTransferQueryFlow.class) ? flows.domainTransferQueryFlow()
          : clazz.equals(DomainTransferRejectFlow.class) ? flows.domainTransferRejectFlow()
          : clazz.equals(DomainTransferRequestFlow.class) ? flows.domainTransferRequestFlow()
          : clazz.equals(DomainUpdateFlow.class) ? flows.domainUpdateFlow()
          : clazz.equals(HostCheckFlow.class) ? flows.hostCheckFlow()
          : clazz.equals(HostCreateFlow.class) ? flows.hostCreateFlow()
          : clazz.equals(HostDeleteFlow.class) ? flows.hostDeleteFlow()
          : clazz.equals(HostInfoFlow.class) ? flows.hostInfoFlow()
          : clazz.equals(HostUpdateFlow.class) ? flows.hostUpdateFlow()
          : clazz.equals(PollAckFlow.class) ? flows.pollAckFlow()
          : clazz.equals(PollRequestFlow.class) ? flows.pollRequestFlow()
          : clazz.equals(HelloFlow.class) ? flows.helloFlow()
          : clazz.equals(LoginFlow.class) ? flows.loginFlow()
          : clazz.equals(LogoutFlow.class) ? flows.logoutFlow()
          : null;
    }
  }
}
