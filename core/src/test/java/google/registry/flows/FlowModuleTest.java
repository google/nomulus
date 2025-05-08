// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.newTld;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dagger.Component;
import google.registry.flows.FlowComponent.FlowComponentModule;
import google.registry.model.eppinput.EppInput;
import google.registry.persistence.transaction.DatabaseException;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.testing.EppLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link FlowModule}. */
public class FlowModuleTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private EppInput getEppInput(String eppInputXmlFilename) throws EppException {
    return new EppLoader(this, eppInputXmlFilename).getEpp();
  }

  @Test
  void givenMutatingFlow_thenPrimaryTmIsUsed() throws EppException {
    String eppInputXmlFilename = "domain_create.xml";
    FlowModule flowModule =
        new FlowModule.Builder().setEppInput(getEppInput(eppInputXmlFilename)).build();
    JpaTransactionManager tm =
        DaggerFlowModuleTest_FlowModuleTestComponent.builder()
            .flowModule(flowModule)
            .build()
            .jpaTm();
    assertThat(tm).isEqualTo(tm());
    tm.transact(() -> tm.put(newTld("app", "ROID")));
  }

  @Test
  void givenNonMutatingFlow_thenReplicaTmIsUsed() throws EppException {
    String eppInputXmlFilename = "domain_check.xml";
    FlowModule flowModule =
        new FlowModule.Builder().setEppInput(getEppInput(eppInputXmlFilename)).build();
    JpaTransactionManager tm =
        DaggerFlowModuleTest_FlowModuleTestComponent.builder()
            .flowModule(flowModule)
            .build()
            .jpaTm();
    assertThat(tm).isEqualTo(replicaTm());
    assertThat(
            assertThrows(
                DatabaseException.class, () -> tm.transact(() -> tm.put(newTld("app", "ROID")))))
        .hasMessageThat()
        .contains("cannot execute INSERT in a read-only transaction");
  }

  @FlowScope
  @Component(modules = {FlowModule.class, FlowComponentModule.class})
  public interface FlowModuleTestComponent {
    JpaTransactionManager jpaTm();

    @Component.Builder
    interface Builder {
      Builder flowModule(FlowModule flowModule);

      FlowModuleTestComponent build();
    }
  }
}
