// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.custom;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;
import java.util.Optional;

/** Dagger module to provide instances of custom logic classes for EPP flows. */
@Module
public abstract class CustomLogicModule {

  @Provides
  static DomainCreateFlowCustomLogic provideDomainCreateFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainCreateFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainCheckFlowCustomLogic provideDomainCheckFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainCheckFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainInfoFlowCustomLogic provideDomainInfoFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainInfoFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainUpdateFlowCustomLogic provideDomainUpdateFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainUpdateFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainRenewFlowCustomLogic provideDomainRenewFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainRenewFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainDeleteFlowCustomLogic provideDomainDeleteFlowCustomLogic(
      CustomLogicFactory factory,
      EppInput eppInput,
      SessionMetadata sessionMetadata,
      FlowMetadata flowMetadata) {
    return factory.forDomainDeleteFlow(eppInput, sessionMetadata, flowMetadata);
  }

  @Provides
  static DomainPricingCustomLogic provideDomainPricingCustomLogic(
      CustomLogicFactory factory,
      Optional<EppInput> eppInput,
      Optional<SessionMetadata> sessionMetadata,
      Optional<FlowMetadata> flowMetadata) {
    // Note that, for DomainPricingCustomLogic, the EPP flow state won't be present outside the
    // context of an EPP flow.
    return factory.forDomainPricing(eppInput, sessionMetadata, flowMetadata);
  }

  @BindsOptionalOf
  abstract EppInput optionalEppInput();

  @BindsOptionalOf
  abstract SessionMetadata optionalSessionMetadata();

  @BindsOptionalOf
  abstract FlowMetadata optionalFlowMetadata();
}
