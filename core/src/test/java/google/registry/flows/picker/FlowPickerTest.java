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

package google.registry.flows.picker;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.Flow;
import google.registry.flows.domain.DomainCheckFlow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.model.domain.DomainCommand;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppinput.EppInput;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FlowPicker}. */
class FlowPickerTest {

  @Test
  void testGetFlowClass_matchingWrapperAndCommand_returnsFlow() throws Exception {
    EppInput checkEppInput = EppInput.create(EppInput.Check.create(new DomainCommand.Check()));
    Class<? extends Flow> checkFlow = FlowPicker.getFlowClass(checkEppInput);
    assertThat(checkFlow).isEqualTo(DomainCheckFlow.class);

    EppInput createEppInput = EppInput.create(EppInput.Create.create(new DomainCommand.Create()));
    Class<? extends Flow> createFlow = FlowPicker.getFlowClass(createEppInput);
    assertThat(createFlow).isEqualTo(DomainCreateFlow.class);
  }

  @Test
  void testGetFlowClass_mismatchedWrapperAndCommand_throwsSyntaxErrorException() {
    EppInput mismatchedEppInput1 =
        EppInput.create(EppInput.Check.create(new DomainCommand.Create()));
    assertThat(
            assertThrows(
                FlowPicker.MismatchedCommandException.class,
                () -> FlowPicker.getFlowClass(mismatchedEppInput1)))
        .hasMessageThat()
        .isEqualTo("EPP command wrapper <check> does not match resource command <create>");

    EppInput mismatchedEppInput2 =
        EppInput.create(EppInput.Create.create(new DomainCommand.Check()));
    assertThat(
            assertThrows(
                FlowPicker.MismatchedCommandException.class,
                () -> FlowPicker.getFlowClass(mismatchedEppInput2)))
        .hasMessageThat()
        .contains("EPP command wrapper <create> does not match resource command <check>");
  }

  @Test
  void testGetFlowClass_mismatchedXml_throwsMismatchedCommandException() throws Exception {
    String mismatchedXml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <check>
              <domain:create xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.com</domain:name>
                <domain:authInfo>
                  <domain:pw>fooBAR123</domain:pw>
                </domain:authInfo>
              </domain:create>
            </check>
            <clTRID>ABC-12345</clTRID>
          </command>
        </epp>
        """;

    // Verify JAXB successfully unmarshals the mismatched XML without throwing any errors
    EppInput eppInput = EppXmlTransformer.unmarshal(EppInput.class, mismatchedXml.getBytes(UTF_8));
    assertThat(eppInput).isNotNull();

    // Verify that FlowPicker intercepts the unmarshalled input and blocks it
    assertThat(
            assertThrows(
                FlowPicker.MismatchedCommandException.class,
                () -> FlowPicker.getFlowClass(eppInput)))
        .hasMessageThat()
        .contains("EPP command wrapper <check> does not match resource command <create>");
  }
}
