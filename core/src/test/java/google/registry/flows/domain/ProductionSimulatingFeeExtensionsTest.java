// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.domain;

import google.registry.flows.Flow;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.util.RegistryEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Abstract class for tests that require old versions of the fee extension (0.6, 0.11, 0.12).
 *
 * <p>These are enabled only in the production environment so in order to test them, we need to
 * simulate the production environment for each test (and reset it to the test environment
 * afterward).
 */
public abstract class ProductionSimulatingFeeExtensionsTest<F extends Flow>
    extends ResourceFlowTestCase<F, Domain> {

  private RegistryEnvironment previousEnvironment;

  @BeforeEach
  void beforeEach() {
    previousEnvironment = RegistryEnvironment.get();
    RegistryEnvironment.PRODUCTION.setup();
    reloadServiceExtensionUris();
  }

  @AfterEach
  void afterEach() {
    previousEnvironment.setup();
    reloadServiceExtensionUris();
  }

  void reloadServiceExtensionUris() {
    ProtocolDefinition.reloadServiceExtensionUris();
    sessionMetadata.setServiceExtensionUris(ProtocolDefinition.getVisibleServiceExtensionUris());
    EppXmlTransformer.reloadTransformers();
  }
}
