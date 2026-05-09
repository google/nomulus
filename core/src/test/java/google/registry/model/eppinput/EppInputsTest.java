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

package google.registry.model.eppinput;

import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppcommon.StatusValue;
import google.registry.xml.ValidationMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EppInputs} and {@link EppExtensions}. */
class EppInputsTest {

  @Test
  void testCreateDomain() throws Exception {
    EppInput eppInput =
        EppInputs.createDomain("example.tld", "password")
            .setPeriod(2, Period.Unit.YEARS)
            .setRegistrant("contactId")
            .addAdminTechContact("contactId")
            .setNameservers(ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .addExtension(EppExtensions.toolMetadata("reason", true))
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
                <domain:period unit="y">2</domain:period>
                <domain:registrant>contactId</domain:registrant>
                <domain:contact type="admin">contactId</domain:contact>
                <domain:contact type="tech">contactId</domain:contact>
                <domain:ns>
                  <domain:hostObj>ns1.example.com</domain:hostObj>
                  <domain:hostObj>ns2.example.com</domain:hostObj>
                </domain:ns>
                <domain:authInfo>
                  <domain:pw>password</domain:pw>
                </domain:authInfo>
              </domain:create>
            </create>
            <extension>
              <metadata:metadata xmlns:metadata="urn:google:params:xml:ns:metadata-1.0">
                <metadata:reason>reason</metadata:reason>
                <metadata:requestedByRegistrar>true</metadata:requestedByRegistrar>
              </metadata:metadata>
            </extension>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testUpdateDomain_omitEmptyTags() throws Exception {
    EppInput eppInput = EppInputs.updateDomain("example.tld").build();
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
              </domain:update>
            </update>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testUpdateDomain_allFields() throws Exception {
    EppInput eppInput =
        EppInputs.updateDomain("example.tld")
            .addNameservers(ImmutableSet.of("ns1.example.com"))
            .removeStatuses(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .setNewPassword("new-pw")
            .setAutorenews(false)
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
                  <domain:ns>
                    <domain:hostObj>ns1.example.com</domain:hostObj>
                  </domain:ns>
                </domain:add>
                <domain:rem>
                  <domain:status s="clientHold"/>
                </domain:rem>
                <domain:chg>
                  <domain:authInfo>
                    <domain:pw>new-pw</domain:pw>
                  </domain:authInfo>
                </domain:chg>
              </domain:update>
            </update>
            <extension>
              <superuser:domainUpdate xmlns:superuser="urn:google:params:xml:ns:superuser-1.0">
                <superuser:autorenews>false</superuser:autorenews>
              </superuser:domainUpdate>
            </extension>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testRenewDomain() throws Exception {
    EppInput eppInput =
        EppInputs.renewDomain("example.tld", 1, LocalDate.parse("2026-05-08")).build();
    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <renew>
              <domain:renew xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>example.tld</domain:name>
                <domain:curExpDate>2026-05-08</domain:curExpDate>
                <domain:period unit="y">1</domain:period>
              </domain:renew>
            </renew>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testDomainCheck_withExtensions() throws Exception {
    EppInput eppInput =
        EppInputs.checkDomain(ImmutableList.of("a.tld", "b.tld"))
            .addExtension(EppExtensions.feeCheckCreateV06(ImmutableList.of("a.tld", "b.tld")))
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <check>
              <domain:check xmlns:domain="urn:ietf:params:xml:ns:domain-1.0">
                <domain:name>a.tld</domain:name>
                <domain:name>b.tld</domain:name>
              </domain:check>
            </check>
            <extension>
              <fee:check xmlns:fee="urn:ietf:params:xml:ns:fee-0.6">
                <fee:domain>
                  <fee:name>a.tld</fee:name>
                  <fee:command>create</fee:command>
                  <fee:period unit="y">1</fee:period>
                </fee:domain>
                <fee:domain>
                  <fee:name>b.tld</fee:name>
                  <fee:command>create</fee:command>
                  <fee:period unit="y">1</fee:period>
                </fee:domain>
              </fee:check>
            </extension>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }

  @Test
  void testCreateHost() throws Exception {
    EppInput eppInput =
        EppInputs.createHost("ns1.example.tld")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("127.0.0.1"), InetAddresses.forString("::1")))
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertXmlEquals(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <epp xmlns="urn:ietf:params:xml:ns:epp-1.0">
          <command>
            <create>
              <host:create xmlns:host="urn:ietf:params:xml:ns:host-1.0">
                <host:name>ns1.example.tld</host:name>
                <host:addr ip="v4">127.0.0.1</host:addr>
                <host:addr ip="v6">::1</host:addr>
              </host:create>
            </create>
            <clTRID>RegistryTool</clTRID>
          </command>
        </epp>
        """,
        xml);
  }
}
