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
import static google.registry.testing.DatastoreHelper.createTld;

import com.google.common.io.ByteSource;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineRule;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class XjcToContactResourceConverterTest {

  private static final ByteSource CONTACT_XML = RdeImportsTestData.get("contact_fragment.xml");

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void before() throws Exception {
    createTld("xn--q9jyb4c");
  }

  @Test
  public void testConvertContact() throws Exception {
    XjcRdeContact contact = getContact();
    ContactResource resource = XjcToContactResourceConverter.convertContact(contact);
    assertThat(resource.getContactId()).isEqualTo("love-id");
    assertThat(resource.getRepoId()).isEqualTo("2-ROID");
    assertThat(resource.getStatusValues())
        .containsExactly(
            StatusValue.CLIENT_DELETE_PROHIBITED,
            StatusValue.SERVER_UPDATE_PROHIBITED);

    assertThat(resource.getInternationalizedPostalInfo()).isNotNull();
    PostalInfo postalInfo = resource.getInternationalizedPostalInfo();
    assertThat(postalInfo.getName()).isEqualTo("Dipsy Doodle");
    assertThat(postalInfo.getOrg()).isEqualTo("Charleston Road Registry Incorporated");
    assertThat(postalInfo.getAddress().getStreet()).hasSize(2);
    assertThat(postalInfo.getAddress().getStreet().get(0)).isEqualTo("123 Charleston Road");
    assertThat(postalInfo.getAddress().getStreet().get(1)).isEqualTo("Suite 123");
    assertThat(postalInfo.getAddress().getState()).isEqualTo("CA");
    assertThat(postalInfo.getAddress().getZip()).isEqualTo("31337");
    assertThat(postalInfo.getAddress().getCountryCode()).isEqualTo("US");

    assertThat(resource.getLocalizedPostalInfo()).isNull();

    assertThat(resource.getVoiceNumber()).isNotNull();
    assertThat(resource.getVoiceNumber().getPhoneNumber()).isEqualTo("+1.2126660000");
    assertThat(resource.getVoiceNumber().getExtension()).isEqualTo("123");

    assertThat(resource.getFaxNumber()).isNotNull();
    assertThat(resource.getFaxNumber().getPhoneNumber()).isEqualTo("+1.2126660001");
    assertThat(resource.getFaxNumber().getExtension()).isNull();

    assertThat(resource.getEmailAddress()).isEqualTo("justine@crr.com");
    assertThat(resource.getCurrentSponsorClientId()).isEqualTo("TheRegistrar");
    assertThat(resource.getCreationClientId()).isEqualTo("NewRegistrar");
    assertThat(resource.getCreationTime()).isEqualTo(DateTime.parse("1900-01-01TZ"));
    assertThat(resource.getLastEppUpdateClientId()).isEqualTo("TheRegistrar");
    assertThat(resource.getLastEppUpdateTime()).isEqualTo(DateTime.parse("1930-04-20TZ"));
    assertThat(resource.getLastTransferTime()).isEqualTo(DateTime.parse("1925-04-20TZ"));

    assertThat(resource.getTransferData()).isNotNull();
    assertThat(resource.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(resource.getTransferData().getGainingClientId()).isEqualTo("TheRegistrar");
    assertThat(resource.getTransferData().getTransferRequestTime())
        .isEqualTo(DateTime.parse("1925-04-19TZ"));
    assertThat(resource.getTransferData().getLosingClientId()).isEqualTo("NewRegistrar");
    assertThat(resource.getTransferData().getPendingTransferExpirationTime())
        .isEqualTo(DateTime.parse("1925-04-21TZ"));

    assertThat(resource.getDisclose()).isNotNull();
    assertThat(resource.getDisclose().getFlag()).isTrue();
    assertThat(resource.getDisclose().getAddrs()).hasSize(1);
    assertThat(resource.getDisclose().getAddrs().get(0).getType())
        .isEqualTo(PostalInfo.Type.INTERNATIONALIZED);
    assertThat(resource.getDisclose().getNames()).hasSize(1);
    assertThat(resource.getDisclose().getNames().get(0).getType())
        .isEqualTo(PostalInfo.Type.INTERNATIONALIZED);
    assertThat(resource.getDisclose().getOrgs()).isEmpty();
  }

  @Test
  public void testConvertContact_absentVoiceAndFaxNumbers() throws Exception {
    XjcRdeContact contact = getContact();
    contact.setVoice(null);
    contact.setFax(null);
    ContactResource resource = XjcToContactResourceConverter.convertContact(contact);
    assertThat(resource.getVoiceNumber()).isNull();
    assertThat(resource.getFaxNumber()).isNull();
  }

  @Test
  public void testConvertContact_absentDisclose() throws Exception {
    XjcRdeContact contact = getContact();
    contact.setDisclose(null);
    ContactResource resource = XjcToContactResourceConverter.convertContact(contact);
    assertThat(resource.getDisclose()).isNull();
  }

  @Test
  public void testConvertContact_absentTransferData() throws Exception {
    XjcRdeContact contact = getContact();
    contact.setTrDate(null);
    contact.setTrnData(null);
    ContactResource resource = XjcToContactResourceConverter.convertContact(contact);
    assertThat(resource.getLastTransferTime()).isNull();
    assertThat(resource.getTransferData()).isSameAs(TransferData.EMPTY);
  }

  private XjcRdeContact getContact() throws Exception {
    InputStream in = null;
    try {
      in = CONTACT_XML.openBufferedStream();
      XMLInputFactory factory = XMLInputFactory.newInstance();
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer t = tf.newTransformer();
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      t.transform(new StAXSource(reader), new StreamResult(bout));
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      XjcRdeContactElement element = XjcXmlTransformer.unmarshal(XjcRdeContactElement.class, bin);
      return element.getValue();
    } finally {
      if (in != null) {
        in.close();
      }
    }
  }
}
