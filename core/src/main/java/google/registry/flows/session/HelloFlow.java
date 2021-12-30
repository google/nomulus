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

package google.registry.flows.session;

import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.model.eppoutput.Greeting;
import google.registry.util.Clock;
import javax.inject.Inject;

/**
 * A flow for an Epp "hello".
 *
 * @error {@link google.registry.flows.FlowUtils.GenericXmlSyntaxErrorException}
 */
public final class HelloFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject Clock clock;
  @Inject @Config("greetingServerId") String greetingServerId;
  @Inject HelloFlow() {}

  @Override
  public Greeting run() throws EppException {
    extensionManager.validate(); // There are no legal extensions for this flow.
    return Greeting.create(clock.nowUtc(), greetingServerId);
  }
}
