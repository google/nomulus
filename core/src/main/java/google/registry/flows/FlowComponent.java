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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
      String simpleName = clazz.getSimpleName();
      // The method name is the same as the class name but with the first character being lowercase
      String methodName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
      try {
        Method method = FlowComponent.class.getMethod(methodName);
        method.setAccessible(true);
        return (Flow) method.invoke(flows);
      } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
