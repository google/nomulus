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

import static com.google.common.truth.Truth.assertThat;
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
    assertThat(xml).contains("<domain:name>example.tld</domain:name>");
    assertThat(xml).contains("<domain:period unit=\"y\">2</domain:period>");
    assertThat(xml).contains("<domain:registrant>contactId</domain:registrant>");
    assertThat(xml).contains("<domain:contact type=\"admin\">contactId</domain:contact>");
    assertThat(xml).contains("<domain:contact type=\"tech\">contactId</domain:contact>");
    assertThat(xml).contains("<domain:hostObj>ns1.example.com</domain:hostObj>");
    assertThat(xml).contains("<domain:hostObj>ns2.example.com</domain:hostObj>");
    assertThat(xml).contains("<metadata:reason>reason</metadata:reason>");
    assertThat(xml).contains("<metadata:requestedByRegistrar>true</metadata:requestedByRegistrar>");
    assertThat(xml).contains("<clTRID>RegistryTool</clTRID>");
  }

  @Test
  void testUpdateDomain_omitEmptyTags() throws Exception {
    EppInput eppInput = EppInputs.updateDomain("example.tld").build();
    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertThat(xml).contains("<domain:name>example.tld</domain:name>");
    assertThat(xml).doesNotContain("<domain:add");
    assertThat(xml).doesNotContain("<domain:rem");
    assertThat(xml).doesNotContain("<domain:chg");
  }

  @Test
  void testUpdateDomain_withChanges() throws Exception {
    EppInput eppInput =
        EppInputs.updateDomain("example.tld")
            .addNameservers(ImmutableSet.of("ns1.example.com"))
            .removeStatuses(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .setNewPassword("new-pw")
            .setAutorenews(false)
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertThat(xml).contains("<domain:add>");
    assertThat(xml).contains("<domain:hostObj>ns1.example.com</domain:hostObj>");
    assertThat(xml).contains("<domain:rem>");
    assertThat(xml).contains("<domain:status s=\"clientHold\"/>");
    assertThat(xml).contains("<domain:chg>");
    assertThat(xml).contains("<domain:pw>new-pw</domain:pw>");
    assertThat(xml).contains("<superuser:autorenews>false</superuser:autorenews>");
  }

  @Test
  void testRenewDomain() throws Exception {
    EppInput eppInput =
        EppInputs.renewDomain("example.tld", 1, LocalDate.parse("2026-05-08")).build();
    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertThat(xml).contains("<domain:name>example.tld</domain:name>");
    assertThat(xml).contains("<domain:curExpDate>2026-05-08</domain:curExpDate>");
    assertThat(xml).contains("<domain:period unit=\"y\">1</domain:period>");
  }

  @Test
  void testCheckDomain() throws Exception {
    EppInput eppInput =
        EppInputs.checkDomain(ImmutableList.of("a.tld", "b.tld"))
            .addExtension(EppExtensions.feeCheckCreateV06(ImmutableList.of("a.tld", "b.tld")))
            .build();

    String xml =
        new String(EppXmlTransformer.marshalInput(eppInput, ValidationMode.LENIENT), UTF_8);
    assertThat(xml).contains("<domain:name>a.tld</domain:name>");
    assertThat(xml).contains("<domain:name>b.tld</domain:name>");
    assertThat(xml).contains("<fee:check");
    assertThat(xml).contains("<fee:name>a.tld</fee:name>");
    assertThat(xml).contains("<fee:name>b.tld</fee:name>");
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
    assertThat(xml).contains("<host:name>ns1.example.tld</host:name>");
    assertThat(xml).contains("<host:addr ip=\"v4\">127.0.0.1</host:addr>");
    assertThat(xml).contains("<host:addr ip=\"v6\">::1</host:addr>");
  }
}
