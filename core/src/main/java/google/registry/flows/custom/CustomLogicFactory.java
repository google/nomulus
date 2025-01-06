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

import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;
import java.util.Optional;

/**
 * A no-op base custom logic factory.
 *
 * <p>To add custom logic, extend this class, then configure it in
 * {@link ConfigModule#provideCustomLogicFactoryClass}. The eppInput and sessionMetadata parameters
 * are unused in the base implementation, but are provided so that custom implementations can
 * optionally determine how to construct/choose which custom logic class to return. A common use
 * case might be parsing TLD for domain-specific flows from the EppInput and then using that to
 * choose a different custom logic implementation, or switching based on the registrar
 * {@code clientId} in sessionMetadata.
 */
public class CustomLogicFactory {

  public DomainCreateFlowCustomLogic forDomainCreateFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainCreateFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainCheckFlowCustomLogic forDomainCheckFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainCheckFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainInfoFlowCustomLogic forDomainInfoFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainInfoFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainUpdateFlowCustomLogic forDomainUpdateFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainUpdateFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainRenewFlowCustomLogic forDomainRenewFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainRenewFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainDeleteFlowCustomLogic forDomainDeleteFlow(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    return new DomainDeleteFlowCustomLogic(eppInput, sessionMetadata, flowMetadata);
  }

  public DomainPricingCustomLogic forDomainPricing(
      Optional<EppInput> eppInput,
      Optional<SessionMetadata> sessionMetadata,
      Optional<FlowMetadata> flowMetadata) {
    return new DomainPricingCustomLogic(
        eppInput.orElse(null), sessionMetadata.orElse(null), flowMetadata.orElse(null));
  }
}
