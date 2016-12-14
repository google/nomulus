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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.io.ByteSource;
import google.registry.rde.imports.RdeParser.RdeHeader;
import google.registry.testing.ExceptionRule;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdeeppparams.XjcRdeEppParams;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdeidn.XjcRdeIdn;
import google.registry.xjc.rdenndn.XjcRdeNndn;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeParser}. */
@RunWith(JUnit4.class)
public class RdeParserTest {

  private static final ByteSource DEPOSIT_XML = RdeImportsTestData.get("deposit_full_parser.xml");

  private InputStream xml;

  @Rule public final ExceptionRule thrown = new ExceptionRule();

  private void checkHeader(RdeHeader header) {
    assertThat(header.getTld()).isEqualTo("test");
    assertThat(header.getContactCount()).isEqualTo(1L);
    assertThat(header.getDomainCount()).isEqualTo(2L);
    assertThat(header.getEppParamsCount()).isEqualTo(1L);
    assertThat(header.getHostCount()).isEqualTo(1L);
    assertThat(header.getIdnCount()).isEqualTo(1L);
    assertThat(header.getNndnCount()).isEqualTo(1L);
    assertThat(header.getRegistrarCount()).isEqualTo(1L);
  }

  @Before
  public void before() throws IOException {
    xml = new ByteArrayInputStream(DEPOSIT_XML.read());
  }

  @After
  public void after() throws IOException {
    xml.close();
  }

  @Test
  public void testGetHeader_returnsHeader() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetContactNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class,
          "Not at element urn:ietf:params:xml:ns:rdeContact-1.0:contact");
      parser.getContact();
    }
  }

  @Test
  public void testGetContactAtElement_returnsContact() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextContact();
      XjcRdeContact contact = parser.getContact();
      assertThat(contact.getId()).isEqualTo("sh8013");
      assertThat(contact.getClID()).isEqualTo("RegistrarX");
    }
  }

  @Test
  public void testNextContact_advancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtContact()).isFalse();
      // there is only one contact in the escrow file
      assertThat(parser.nextContact()).isTrue();
      assertThat(parser.isAtContact()).isTrue();
      assertThat(parser.nextContact()).isFalse();
      assertThat(parser.isAtContact()).isFalse();
    }
  }

  @Test
  public void testSkipZeroContacts_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipContacts(0)).isEqualTo(0);
      assertThat(parser.nextContact()).isTrue();
    }
  }

  @Test
  public void testSkipOneContactFromBeginning_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipContacts(1)).isEqualTo(1);
      assertThat(parser.isAtContact()).isFalse();
    }
  }

  @Test
  public void testSkipOneContactFromFirstContact_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextContact();
      assertThat(parser.skipContacts(1)).isEqualTo(1);
      assertThat(parser.isAtContact()).isFalse();
    }
  }

  @Test
  public void testSkip9999Contacts_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipContacts(9999)).isEqualTo(1);
      assertThat(parser.isAtContact()).isFalse();
    }
  }

  @Test
  public void testSkipContactsFromEnd_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextContact();
      parser.nextContact();
      assertThat(parser.skipContacts(1)).isEqualTo(0);
      assertThat(parser.isAtContact()).isFalse();
    }
  }

  @Test
  public void testGetHeaderAfterNextContact_returnsHeader() throws Exception {
    // verify that the header is still available after advancing to next contact
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextContact();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetDomainNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class,
          "Not at element urn:ietf:params:xml:ns:rdeDomain-1.0:domain");
      parser.getDomain();
    }
  }

  @Test
  public void testGetDomainAtElement_returnsDomain() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextDomain();
      XjcRdeDomain domain = parser.getDomain();
      assertThat(domain.getName()).isEqualTo("example1.test");
    }
  }

  @Test
  public void testNextDomain_advancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      // there are 2 domains in the escrow file
      assertThat(parser.isAtDomain()).isFalse();
      assertThat(parser.nextDomain()).isTrue();
      assertThat(parser.isAtDomain()).isTrue();
      assertThat(parser.nextDomain()).isTrue();
      assertThat(parser.isAtDomain()).isTrue();
      assertThat(parser.nextDomain()).isFalse();
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkipZeroDomains_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipDomains(0)).isEqualTo(0);
      assertThat(parser.nextDomain()).isTrue();
    }
  }

  @Test
  public void testSkipOneDomainFromBeginning_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipDomains(1)).isEqualTo(1);
      // there are two domains
      assertThat(parser.isAtDomain()).isTrue();
      // prove that the parser advanced to the second domain
      assertThat(parser.nextDomain()).isFalse();
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkipTwoDomainsFromBeginning_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipDomains(2)).isEqualTo(2);
      // there are two domains
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkipOneDomainFromFirstDomain_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextDomain();
      assertThat(parser.skipDomains(1)).isEqualTo(1);
      // there are two domains
      assertThat(parser.isAtDomain()).isTrue();
      // prove that the parser advanced to the second domain
      assertThat(parser.nextDomain()).isFalse();
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkipTwoDomainsFromFirstDomain_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextDomain();
      assertThat(parser.skipDomains(2)).isEqualTo(2);
      // there are two domains
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkip9999Domains_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipDomains(9999)).isEqualTo(2);
      assertThat(parser.isAtDomain()).isFalse();
    }
  }

  @Test
  public void testSkipDomainsFromEnd_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextDomain();
      parser.nextDomain();
      parser.nextDomain();
      assertThat(parser.skipDomains(1)).isEqualTo(0);
    }
  }

  @Test
  public void testGetHeaderAfterNextDomain_returnsHeader() throws Exception {
    // verify that the header is still available after advancing to next domain
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextDomain();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetHostNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class, "Not at element urn:ietf:params:xml:ns:rdeHost-1.0:host");
      parser.getHost();
    }
  }

  @Test
  public void testGetHostAtElement_returnsHost() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextHost();
      XjcRdeHost host = parser.getHost();
      assertThat(host.getName()).isEqualTo("ns1.example.com");
    }
  }

  @Test
  public void testNextHost_advancesParser() throws Exception {
    // the header lies, there are 2 hosts in the file
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtHost()).isFalse();
      assertThat(parser.nextHost()).isTrue();
      assertThat(parser.isAtHost()).isTrue();
      assertThat(parser.nextHost()).isTrue();
      assertThat(parser.isAtHost()).isTrue();
      assertThat(parser.nextHost()).isFalse();
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkipZeroHosts_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipHosts(0)).isEqualTo(0);
      assertThat(parser.nextHost()).isTrue();
    }
  }

  @Test
  public void testSkipOneHostFromBeginning_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipHosts(1)).isEqualTo(1);
      // there are two hosts
      assertThat(parser.isAtHost()).isTrue();
      // prove that the parser advanced to the second host
      assertThat(parser.nextHost()).isFalse();
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkipTwoHostsFromBeginning_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipHosts(2)).isEqualTo(2);
      // there are two hosts
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkipOneHostFromFirstHost_skipsOne() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextHost();
      assertThat(parser.skipHosts(1)).isEqualTo(1);
      // there are two hosts
      assertThat(parser.isAtHost()).isTrue();
      // prove that the parser advanced to the second host
      assertThat(parser.nextHost()).isFalse();
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkipTwoHostsFromFirstHost_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextHost();
      assertThat(parser.skipHosts(2)).isEqualTo(2);
      // there are two hosts
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkip9999Hosts_skipsTwo() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.skipHosts(9999)).isEqualTo(2);
      // there are two hosts
      assertThat(parser.isAtHost()).isFalse();
    }
  }

  @Test
  public void testSkipHostFromEnd_skipsZero() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextHost();
      parser.nextHost();
      parser.nextHost();
      assertThat(parser.skipHosts(1)).isEqualTo(0);
    }
  }

  @Test
  public void testGetHeaderAfterNextHost_returnsHeader() throws Exception {
    // verify that the header is still available after advancing to next host
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextHost();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetRegistrarNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class,
          "Not at element urn:ietf:params:xml:ns:rdeRegistrar-1.0:registrar");
      parser.getRegistrar();
    }
  }

  @Test
  public void testGetRegistrarAtElement_returnsRegistrar() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextRegistrar();
      XjcRdeRegistrar registrar = parser.getRegistrar();
      assertThat(registrar.getId()).isEqualTo("RegistrarX");
      assertThat(registrar.getName()).isEqualTo("Registrar X");
    }
  }

  @Test
  public void testNextRegistrar_advancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtRegistrar()).isFalse();
      assertThat(parser.nextRegistrar()).isTrue();
      assertThat(parser.isAtRegistrar()).isTrue();
      assertThat(parser.nextRegistrar()).isFalse();
      assertThat(parser.isAtRegistrar()).isFalse();
    }
  }

  @Test
  public void testGetHeaderAfterNextRegistrar_returnsHeader() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextRegistrar();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetNndnNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class, "Not at element urn:ietf:params:xml:ns:rdeNNDN-1.0:NNDN");
      parser.getNndn();
    }
  }

  @Test
  public void testGetNndnAtElement_returnsNndn() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextNndn();
      XjcRdeNndn nndn = parser.getNndn();
      assertThat(nndn.getAName()).isEqualTo("xn--exampl-gva.test");
    }
  }

  @Test
  public void testNextNndn_advancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtNndn()).isFalse();
      assertThat(parser.nextNndn()).isTrue();
      assertThat(parser.isAtNndn()).isTrue();
      assertThat(parser.nextNndn()).isFalse();
      assertThat(parser.isAtNndn()).isFalse();
    }
  }

  @Test
  public void testGetHeaderAfterNextNndn_returnsHeader() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextNndn();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetIdnNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class,
          "Not at element urn:ietf:params:xml:ns:rdeIDN-1.0:idnTableRef");
      parser.getIdn();
    }
  }

  @Test
  public void testGetIdnAtElement_returnsIdn() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextIdn();
      XjcRdeIdn idn = parser.getIdn();
      // url contains whitespace
      assertThat(idn.getUrl().trim())
          .isEqualTo("http://www.iana.org/domains/idn-tables/tables/br_pt-br_1.0.html");
    }
  }

  @Test
  public void testNextIdn_advancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtIdn()).isFalse();
      assertThat(parser.nextIdn()).isTrue();
      assertThat(parser.isAtIdn()).isTrue();
      assertThat(parser.nextIdn()).isFalse();
      assertThat(parser.isAtIdn()).isFalse();
    }
  }

  @Test
  public void testGetHeaderAfterNextIdn_returnsHeader() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextIdn();
      checkHeader(parser.getHeader());
    }
  }

  @Test
  public void testGetEppParamsNotAtElement_throwsIllegalStateException() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      thrown.expect(
          IllegalStateException.class,
          "Not at element urn:ietf:params:xml:ns:rdeEppParams-1.0:eppParams");
      parser.getEppParams();
    }
  }

  @Test
  public void testGetEppParamsAtElement_returnsEppParams() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      parser.nextEppParams();
      XjcRdeEppParams eppParams = parser.getEppParams();
      assertThat(eppParams.getVersions()).containsExactly("1.0");
    }
  }

  @Test
  public void testNextEppParamsAdvancesParser() throws Exception {
    try (RdeParser parser = new RdeParser(xml)) {
      assertThat(parser.isAtEppParams()).isFalse();
      assertThat(parser.nextEppParams()).isTrue();
      assertThat(parser.isAtEppParams()).isTrue();
      assertThat(parser.nextEppParams()).isFalse();
      assertThat(parser.isAtEppParams()).isFalse();
    }
  }
}
