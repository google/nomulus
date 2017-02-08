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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.imports.RdeImportTestUtils.checkTrid;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Work;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ShardableTestCase;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XjcToHostResourceConverter}
 */
@RunWith(JUnit4.class)
public class XjcToHostResourceConverterTest extends ShardableTestCase {

  private static final ByteSource HOST_XML = RdeImportsTestData.get("host_fragment.xml");
  private static final ByteSource HOST_XML_UCASE =
      RdeImportsTestData.get("host_fragment_ucase.xml");

  // List of packages to initialize JAXBContext
  private static final String JAXB_CONTEXT_PACKAGES = Joiner.on(":")
      .join(ImmutableList.of(
          "google.registry.xjc.contact",
          "google.registry.xjc.domain",
          "google.registry.xjc.host",
          "google.registry.xjc.mark",
          "google.registry.xjc.rde",
          "google.registry.xjc.rdecontact",
          "google.registry.xjc.rdedomain",
          "google.registry.xjc.rdeeppparams",
          "google.registry.xjc.rdeheader",
          "google.registry.xjc.rdeidn",
          "google.registry.xjc.rdenndn",
          "google.registry.xjc.rderegistrar",
          "google.registry.xjc.smd"));

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private Unmarshaller unmarshaller;

  @Before
  public void before() throws Exception {
    createTld("example");
    unmarshaller = JAXBContext.newInstance(JAXB_CONTEXT_PACKAGES).createUnmarshaller();
  }

  @Test
  public void testConvertHostResource() throws Exception {
    XjcRdeHost xjcHost = loadHostFromRdeXml();
    HostResource host = convertHostInTransaction(xjcHost);
    assertThat(host.getFullyQualifiedHostName()).isEqualTo("ns1.example1.test");
    assertThat(host.getRepoId()).isEqualTo("Hns1_example1_test-TEST");
    // The imported XML also had LINKED status, but that should have been dropped on import.
    assertThat(host.getStatusValues()).containsExactly(StatusValue.OK);
    assertThat(host.getInetAddresses())
        .containsExactly(
            InetAddresses.forString("192.0.2.2"),
            InetAddresses.forString("192.0.2.29"),
            InetAddresses.forString("1080:0:0:0:8:800:200C:417A"));
    assertThat(host.getCurrentSponsorClientId()).isEqualTo("RegistrarX");
    assertThat(host.getCreationClientId()).isEqualTo("RegistrarX");
    assertThat(host.getCreationTime()).isEqualTo(DateTime.parse("1999-05-08T12:10:00.0Z"));
    assertThat(host.getLastEppUpdateClientId()).isEqualTo("RegistrarX");
    assertThat(host.getLastEppUpdateTime()).isEqualTo(DateTime.parse("2009-10-03T09:34:00.0Z"));
    assertThat(host.getLastTransferTime()).isEqualTo(DateTime.parse("2008-10-03T09:34:00.0Z"));
  }

  /** Verifies that uppercase host names are converted to lowercase */
  @Test
  public void testConvertHostResourceUpperCase() throws Exception {
    XjcRdeHost xjcHost = loadHostFromRdeXml(HOST_XML_UCASE);
    HostResource host = convertHostInTransaction(xjcHost);
    assertThat(host.getFullyQualifiedHostName()).isEqualTo("ns1.example1.test");
  }

  @Test
  public void testConvertHostResourceHistoryEntry() throws Exception {
    XjcRdeHost xjcHost = loadHostFromRdeXml();
    HostResource host = convertHostInTransaction(xjcHost);
    List<HistoryEntry> historyEntries = getHistoryEntries(host);
    assertThat(historyEntries).hasSize(1);
    HistoryEntry entry = historyEntries.get(0);
    assertThat(entry.getType()).isEqualTo(HistoryEntry.Type.RDE_IMPORT);
    assertThat(entry.getClientId()).isEqualTo("RegistrarX");
    assertThat(entry.getBySuperuser()).isTrue();
    assertThat(entry.getReason()).isEqualTo("RDE Import");
    assertThat(entry.getRequestedByRegistrar()).isFalse();
    checkTrid(entry.getTrid());
    // check xml against original domain xml
    try (InputStream ins = new ByteArrayInputStream(entry.getXmlBytes())) {
      XjcRdeHost unmarshalledXml = ((XjcRdeHostElement) unmarshaller.unmarshal(ins)).getValue();
      assertThat(unmarshalledXml.getName()).isEqualTo(xjcHost.getName());
      assertThat(unmarshalledXml.getRoid()).isEqualTo(xjcHost.getRoid());
    }
  }

  private static HostResource convertHostInTransaction(final XjcRdeHost xjcHost) {
    return ofy().transact(new Work<HostResource>() {
      @Override
      public HostResource run() {
        return XjcToHostResourceConverter.convert(xjcHost);
      }
    });
  }

  private XjcRdeHost loadHostFromRdeXml() throws Exception {
    return loadHostFromRdeXml(HOST_XML);
  }

  private XjcRdeHost loadHostFromRdeXml(ByteSource source) throws Exception {
    try (InputStream ins = source.openStream()) {
      return ((XjcRdeHostElement) unmarshaller.unmarshal(ins)).getValue();
    }
  }
}
