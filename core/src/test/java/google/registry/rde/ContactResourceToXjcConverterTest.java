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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineRule;
import google.registry.xjc.contact.XjcContactPostalInfoEnumType;
import google.registry.xjc.contact.XjcContactPostalInfoType;
import google.registry.xjc.contact.XjcContactStatusType;
import google.registry.xjc.contact.XjcContactStatusValueType;
import google.registry.xjc.eppcom.XjcEppcomTrStatusType;
import google.registry.xjc.rde.XjcRdeContentsType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rde.XjcRdeMenuType;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactElement;
import java.io.ByteArrayOutputStream;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ContactResourceToXjcConverter}.
 *
 * <p>This tests the mapping between {@link ContactResource} and {@link XjcRdeContact} as well as
 * some exceptional conditions.
 */
@RunWith(JUnit4.class)
public class ContactResourceToXjcConverterTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void before() {
    createTld("xn--q9jyb4c");
  }

  @Test
  public void testConvertContact() {
    ContactResource contact = makeContactResource();
    XjcRdeContact bean = ContactResourceToXjcConverter.convertContact(contact);

    // o  A <id> element that contains the server-unique identifier of the
    //    contact object
    assertThat(bean.getId()).isEqualTo("love-id");

    // o  A <roid> element that contains the Repository Object IDentifier
    //    assigned to the contact object when the object was created.
    assertThat(bean.getRoid()).isEqualTo("2-ROID");

    // o  One or more <status> elements that describe the status of the
    //    contact object.
    assertThat(bean.getStatuses().stream().map(XjcContactStatusType::getS))
        .containsExactly(
            XjcContactStatusValueType.CLIENT_DELETE_PROHIBITED,
            XjcContactStatusValueType.SERVER_UPDATE_PROHIBITED);

    // o  One or two <postalInfo> elements that contain postal-address
    //    information.  Two elements are provided so that address
    //    information can be provided in both internationalized and
    //    localized forms; a "type" attribute is used to identify the two
    //    forms.  If an internationalized form (type="int") is provided,
    //    element content MUST be represented in a subset of UTF-8 that can
    //    be represented in the 7-bit US-ASCII character set.  If a
    //    localized form (type="loc") is provided, element content MAY be
    //    represented in unrestricted UTF-8.  The <postalInfo> element
    //    contains the following child elements:
    //
    //    *  A <name> element that contains the name of the individual or
    //       role represented by the contact.
    //
    //    *  An OPTIONAL <org> element that contains the name of the
    //       organization with which the contact is affiliated.
    //
    //    *  An <addr> element that contains address information associated
    //       with the contact.  An <addr> element contains the following
    //       child elements:
    //
    //       +  One, two, or three OPTIONAL <street> elements that contain
    //          the contact's street address.
    //
    //       +  A <city> element that contains the contact's city.
    //
    //       +  An OPTIONAL <sp> element that contains the contact's state
    //          or province.
    //
    //       +  An OPTIONAL <pc> element that contains the contact's postal
    //          code.
    //
    //       +  A <cc> element that contains the contact's two-letter
    //          country code.
    assertThat(bean.getPostalInfos()).hasSize(1);
    XjcContactPostalInfoType postalInfo = bean.getPostalInfos().get(0);
    assertThat(postalInfo.getName()).isEqualTo("Dipsy Doodle");
    assertThat(postalInfo.getOrg()).isEqualTo("Charleston Road Registry Incorporated");
    assertThat(postalInfo.getAddr().getStreets()).hasSize(2);
    assertThat(postalInfo.getAddr().getStreets().get(0)).isEqualTo("123 Charleston Road");
    assertThat(postalInfo.getAddr().getStreets().get(1)).isEqualTo("Suite 123");
    assertThat(postalInfo.getAddr().getSp()).isEqualTo("CA");
    assertThat(postalInfo.getAddr().getPc()).isEqualTo("31337");
    assertThat(postalInfo.getAddr().getCc()).isEqualTo("US");

    // o  An OPTIONAL <voice> element that contains the contact's voice
    //    telephone number.
    assertThat(bean.getVoice()).isNotNull();
    assertThat(bean.getVoice().getValue()).isEqualTo("+1.2126660000");
    assertThat(bean.getVoice().getX()).isEqualTo("123");

    // o  An OPTIONAL <fax> element that contains the contact's facsimile
    //    telephone number.
    assertThat(bean.getFax()).isNotNull();
    assertThat(bean.getFax().getValue()).isEqualTo("+1.2126660001");
    assertThat(bean.getFax().getX()).isNull();

    // o  An <email> element that contains the contact's email address.
    assertThat(bean.getEmail()).isEqualTo("justine@crr.com");

    // o  A <clID> element that contains the identifier of the sponsoring
    //    registrar.
    assertThat(bean.getClID()).isEqualTo("TheRegistrar");

    // o  A <crRr> element that contains the identifier of the registrar
    //    that created the contact object.  An OPTIONAL client attribute is
    //    used to specify the client that performed the operation.
    assertThat(bean.getCrRr().getValue()).isEqualTo("NewRegistrar");

    // o  A <crDate> element that contains the date and time of contact-
    //    object creation.
    assertThat(bean.getCrDate()).isEqualTo(DateTime.parse("1900-01-01TZ"));

    // o  An OPTIONAL <upRr> element that contains the identifier of the
    //    registrar that last updated the contact object.  This element MUST
    //    NOT be present if the contact has never been modified.  An
    //    OPTIONAL client attribute is used to specify the client that
    //    performed the operation.
    assertThat(bean.getUpRr().getValue()).isEqualTo("TheRegistrar");

    // o  An OPTIONAL <upDate> element that contains the date and time of
    //    the most recent contact-object modification.  This element MUST
    //    NOT be present if the contact object has never been modified.
    assertThat(bean.getUpDate()).isEqualTo(DateTime.parse("1930-04-20TZ"));

    // o  An OPTIONAL <trDate> element that contains the date and time of
    //    the most recent contact object successful transfer.  This element
    //    MUST NOT be present if the contact object has never been
    //    transferred.
    assertThat(bean.getTrDate()).isEqualTo(DateTime.parse("1925-04-20TZ"));

    // o  An OPTIONAL <trnData> element that contains the following child
    //    elements related to the last transfer request of the contact
    //    object:
    //
    //    *  A <trStatus> element that contains the state of the most recent
    //       transfer request.
    //
    //    *  A <reRr> element that contains the identifier of the registrar
    //       that requested the domain name object transfer.  An OPTIONAL
    //       client attribute is used to specify the client that performed
    //       the operation.
    //
    //    *  An <acRr> element that contains the identifier of the registrar
    //       that SHOULD act upon a PENDING transfer request.  For all other
    //       status types, the value identifies the registrar that took the
    //       indicated action.  An OPTIONAL client attribute is used to
    //       specify the client that performed the operation.
    //
    //    *  A <reDate> element that contains the date and time that the
    //       transfer was requested.
    //
    //    *  An <acDate> element that contains the date and time of a
    //       required or completed response.  For a PENDING request, the
    //       value identifies the date and time by which a response is
    //       required before an automated response action will be taken by
    //       the registry.  For all other status types, the value identifies
    //       the date and time when the request was completed.
    assertThat(bean.getTrnData()).isNotNull();
    assertThat(bean.getTrnData().getTrStatus()).isEqualTo(XjcEppcomTrStatusType.SERVER_APPROVED);
    assertThat(bean.getTrnData().getReRr().getValue()).isEqualTo("TheRegistrar");
    assertThat(bean.getTrnData().getReDate()).isEqualTo(DateTime.parse("1925-04-19TZ"));
    assertThat(bean.getTrnData().getAcRr().getValue()).isEqualTo("NewRegistrar");
    assertThat(bean.getTrnData().getAcDate()).isEqualTo(DateTime.parse("1925-04-21TZ"));

    // o  An OPTIONAL <disclose> element that identifies elements that
    //    requiring exceptional server-operator handling to allow or
    //    restrict disclosure to third parties.  See Section 2.9 of
    //    [RFC5733] for a description of the child elements contained within
    //    the <disclose> element.
    assertThat(bean.getDisclose()).isNotNull();
    assertThat(bean.getDisclose().isFlag()).isTrue();
    assertThat(bean.getDisclose().getAddrs()).hasSize(1);
    assertThat(bean.getDisclose().getAddrs().get(0).getType())
        .isEqualTo(XjcContactPostalInfoEnumType.INT);
    assertThat(bean.getDisclose().getNames()).hasSize(1);
    assertThat(bean.getDisclose().getNames().get(0).getType())
        .isEqualTo(XjcContactPostalInfoEnumType.INT);
    assertThat(bean.getDisclose().getOrgs()).isEmpty();
  }

  @Test
  public void testConvertContact_absentVoiceAndFaxNumbers() {
    XjcRdeContact bean = ContactResourceToXjcConverter.convertContact(
        makeContactResource().asBuilder()
            .setVoiceNumber(null)
            .setFaxNumber(null)
            .build());
    assertThat(bean.getVoice()).isNull();
    assertThat(bean.getFax()).isNull();
  }

  @Test
  public void testConvertContact_absentDisclose() {
    XjcRdeContact bean = ContactResourceToXjcConverter.convertContact(
        makeContactResource().asBuilder()
            .setDisclose(null)
            .build());
    assertThat(bean.getDisclose()).isNull();
  }

  @Test
  public void testConvertContact_absentTransferData() {
    XjcRdeContact bean = ContactResourceToXjcConverter.convertContact(
        makeContactResource().asBuilder()
            .setLastTransferTime(null)
            .setTransferData(null)
            .build());
    assertThat(bean.getTrDate()).isNull();
    assertThat(bean.getTrnData()).isNull();
  }

  @Test
  public void testMarshal() throws Exception {
    XjcRdeContact bean = ContactResourceToXjcConverter.convertContact(makeContactResource());
    wrapDeposit(bean).marshal(new ByteArrayOutputStream(), UTF_8);
  }

  private static XjcRdeDeposit wrapDeposit(XjcRdeContact contact) {
    XjcRdeDeposit deposit = new XjcRdeDeposit();
    deposit.setId("984302");
    deposit.setType(XjcRdeDepositTypeType.FULL);
    deposit.setWatermark(new DateTime("2012-01-01T04:20:00Z"));
    XjcRdeMenuType menu = new XjcRdeMenuType();
    menu.setVersion("1.0");
    menu.getObjURIs().add("lol");
    deposit.setRdeMenu(menu);
    XjcRdeContactElement element = new XjcRdeContactElement();
    element.setValue(contact);
    XjcRdeContentsType contents = new XjcRdeContentsType();
    contents.getContents().add(element);
    deposit.setContents(contents);
    return deposit;
  }

  private static ContactResource makeContactResource() {
    return new ContactResource.Builder()
        .setContactId("love-id")
        .setRepoId("2-ROID")
        .setCreationClientId("NewRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setLastEppUpdateClientId("TheRegistrar")
        .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setCreationTimeForTest(DateTime.parse("1900-01-01TZ"))
        .setLastTransferTime(DateTime.parse("1925-04-20TZ"))
        .setLastEppUpdateTime(DateTime.parse("1930-04-20TZ"))
        .setEmailAddress("justine@crr.com")
        .setStatusValues(ImmutableSet.of(
            StatusValue.CLIENT_DELETE_PROHIBITED,
            StatusValue.SERVER_UPDATE_PROHIBITED))
        .setInternationalizedPostalInfo(new PostalInfo.Builder()
            .setType(PostalInfo.Type.INTERNATIONALIZED)
            .setName("Dipsy Doodle")
            .setOrg("Charleston Road Registry Incorporated")
            .setAddress(new ContactAddress.Builder()
                .setStreet(ImmutableList.of("123 Charleston Road", "Suite 123"))
                .setCity("Mountain View")
                .setState("CA")
                .setZip("31337")
                .setCountryCode("US")
                .build())
            .build())
        .setVoiceNumber(
            new ContactPhoneNumber.Builder()
                .setPhoneNumber("+1.2126660000")
                .setExtension("123")
                .build())
        .setFaxNumber(
            new ContactPhoneNumber.Builder()
                .setPhoneNumber("+1.2126660001")
                .build())
        .setTransferData(new TransferData.Builder()
            .setGainingClientId("TheRegistrar")
            .setLosingClientId("NewRegistrar")
            .setTransferRequestTime(DateTime.parse("1925-04-19TZ"))
            .setPendingTransferExpirationTime(DateTime.parse("1925-04-21TZ"))
            .setTransferStatus(TransferStatus.SERVER_APPROVED)
            .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
            .build())
        .setDisclose(new Disclose.Builder()
            .setFlag(true)
            .setEmail(new PresenceMarker())
            .setAddrs(ImmutableList.of(
                Disclose.PostalInfoChoice.create(PostalInfo.Type.INTERNATIONALIZED)))
            .setNames(ImmutableList.of(
                Disclose.PostalInfoChoice.create(PostalInfo.Type.INTERNATIONALIZED)))
            .build())
        .build();
  }
}
