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

import static com.google.common.truth.Truth.assertThat;

import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.util.RegistryEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Class for testing the XML extension definitions loaded in the prod environment. */
public class ProductionSimulatingFeeExtensionsTest {

  private RegistryEnvironment previousEnvironment;

  @BeforeEach
  void beforeEach() {
    previousEnvironment = RegistryEnvironment.get();
  }

  @AfterEach
  void afterEach() {
    previousEnvironment.setup();
    ProtocolDefinition.reloadServiceExtensionUris();
  }

  @Test
  void testNonProdEnvironments() {
    for (RegistryEnvironment env : RegistryEnvironment.values()) {
      if (env.equals(RegistryEnvironment.PRODUCTION)) {
        continue;
      }
      env.setup();
      ProtocolDefinition.reloadServiceExtensionUris();
      assertThat(ProtocolDefinition.getVisibleServiceExtensionUris())
          .containsExactly(
              "urn:ietf:params:xml:ns:launch-1.0",
              "urn:ietf:params:xml:ns:rgp-1.0",
              "urn:ietf:params:xml:ns:secDNS-1.1",
              "urn:ietf:params:xml:ns:fee-0.6",
              "urn:ietf:params:xml:ns:fee-0.11",
              "urn:ietf:params:xml:ns:fee-0.12",
              "urn:ietf:params:xml:ns:epp:fee-1.0");
    }
  }

  @Test
  void testProdEnvironment() {
    RegistryEnvironment.PRODUCTION.setup();
    ProtocolDefinition.reloadServiceExtensionUris();
    // prod shouldn't have the fee extension version 1.0
    assertThat(ProtocolDefinition.getVisibleServiceExtensionUris())
        .containsExactly(
            "urn:ietf:params:xml:ns:launch-1.0",
            "urn:ietf:params:xml:ns:rgp-1.0",
            "urn:ietf:params:xml:ns:secDNS-1.1",
            "urn:ietf:params:xml:ns:fee-0.6",
            "urn:ietf:params:xml:ns:fee-0.11",
            "urn:ietf:params:xml:ns:fee-0.12");
  }
}
