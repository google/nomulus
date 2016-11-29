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

package google.registry.flows.custom;

import dagger.Module;
import dagger.Provides;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;

/** Dagger module to provide instances of custom logic classes for EPP flows. */
@Module
public class CustomLogicModule {

  @Provides
  static DomainCreateFlowCustomLogic provideDomainCreateFlowCustomLogic(
      CustomLogicFactory factory, EppInput eppInput, SessionMetadata sessionMetadata) {
    return factory.forDomainCreateFlow(eppInput, sessionMetadata);
  }

  @Provides
  static DomainCheckFlowCustomLogic provideDomainCheckFlowCustomLogic(
      CustomLogicFactory factory, EppInput eppInput, SessionMetadata sessionMetadata) {
    return factory.forDomainCheckFlow(eppInput, sessionMetadata);
  }

  @Provides
  static DomainInfoFlowCustomLogic provideDomainInfoFlowCustomLogic(
      CustomLogicFactory factory, EppInput eppInput, SessionMetadata sessionMetadata) {
    return factory.forDomainInfoFlow(eppInput, sessionMetadata);
  }

  @Provides
  static DomainUpdateFlowCustomLogic provideDomainUpdateFlowCustomLogic(
      CustomLogicFactory factory, EppInput eppInput, SessionMetadata sessionMetadata) {
    return factory.forDomainUpdateFlow(eppInput, sessionMetadata);
  }

  @Provides
  static DomainRenewFlowCustomLogic provideDomainRenewFlowCustomLogic(
      CustomLogicFactory factory, EppInput eppInput, SessionMetadata sessionMetadata) {
    return factory.forDomainRenewFlow(eppInput, sessionMetadata);
  }
}
