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

package google.registry.model.eppinput;

import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainCommand;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppcommon.StatusValue;
import google.registry.xml.ValidationMode;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EppInput} builders and marshaling. */
class EppInputTest {

  @Test
  void testBuilder_emptyExtensions_omitsExtensionTag() throws Exception {
    EppInput eppInput =
        new EppInput.Builder()
            .setCommandWrapper(
                new EppInput.CommandWrapper.Builder()
                    .setCommand(
                        new EppInput.Create.Builder()
                            .setResourceCommand(
                                new DomainCommand.Create.Builder()
                                    .setDomainName("example.tld")
                                    .build())
                            .build())
                    .setExtensions(ImmutableList.of())
                    .setClTrid("RegistryTool")
                    .build())
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <create>
              <domain:create xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.tld</domain:name>
              </domain:create>
            </create>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testBuilder_nullExtensions_omitsExtensionTag() throws Exception {
    EppInput eppInput =
        new EppInput.Builder()
            .setCommandWrapper(
                new EppInput.CommandWrapper.Builder()
                    .setCommand(
                        new EppInput.Create.Builder()
                            .setResourceCommand(
                                new DomainCommand.Create.Builder()
                                    .setDomainName("example.tld")
                                    .build())
                            .build())
                    .setExtensions(null)
                    .setClTrid("RegistryTool")
                    .build())
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <create>
              <domain:create xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.tld</domain:name>
              </domain:create>
            </create>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testBuilder_domainUpdate_emptyAddRemove_omitsInnerTags() throws Exception {
    EppInput eppInput =
        new EppInput.Builder()
            .setCommandWrapper(
                new EppInput.CommandWrapper.Builder()
                    .setCommand(
                        new EppInput.Update.Builder()
                            .setResourceCommand(
                                new DomainCommand.Update.Builder()
                                    .setTargetId("example.tld")
                                    .setInnerAdd(
                                        new DomainCommand.Update.DomainAddRemove.Builder().build())
                                    .setInnerRemove(
                                        new DomainCommand.Update.DomainAddRemove.Builder().build())
                                    .build())
                            .build())
                    .setClTrid("RegistryTool")
                    .build())
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <update>
              <domain:update xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.tld</domain:name>
                <domain:add/>
                <domain:rem/>
              </domain:update>
            </update>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testBuilder_domainUpdate_withStatuses() throws Exception {
    EppInput eppInput =
        new EppInput.Builder()
            .setCommandWrapper(
                new EppInput.CommandWrapper.Builder()
                    .setCommand(
                        new EppInput.Update.Builder()
                            .setResourceCommand(
                                new DomainCommand.Update.Builder()
                                    .setTargetId("example.tld")
                                    .setInnerAdd(
                                        new DomainCommand.Update.DomainAddRemove.Builder()
                                            .setStatusValues(
                                                ImmutableSet.of(StatusValue.CLIENT_HOLD))
                                            .build())
                                    .build())
                            .build())
                    .setClTrid("RegistryTool")
                    .build())
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <update>
              <domain:update xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.tld</domain:name>
                <domain:add>
                  <domain:status s="clientHold"/>
                </domain:add>
              </domain:update>
            </update>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }
}
