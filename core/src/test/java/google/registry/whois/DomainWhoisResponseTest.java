// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.whois.WhoisTestData.loadFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainWhoisResponse}. */
class DomainWhoisResponseTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private Host host1;
  private Host host2;
  private RegistrarPoc abuseContact;
  private Contact adminContact;
  private Contact registrant;
  private Contact techContact;
  private Domain domain;

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @BeforeEach
  void beforeEach() {
    // Update the registrar to have an IANA ID.
    Registrar registrar =
        persistResource(
            loadRegistrar("NewRegistrar")
                .asBuilder()
                .setUrl("http://my.fake.url")
                .setIanaIdentifier(5555555L)
                .build());

    abuseContact =
        persistResource(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jake Doe")
                .setEmailAddress("jakedoe@theregistrar.com")
                .setPhoneNumber("+1.2125551216")
                .setVisibleInDomainWhoisAsAbuse(true)
                .build());

    createTld("tld");

    host1 =
        persistResource(
            new Host.Builder()
                .setHostName("ns01.exampleregistrar.tld")
                .setRepoId("1-ROID")
                .build());

    host2 =
        persistResource(
            new Host.Builder()
                .setHostName("ns02.exampleregistrar.tld")
                .setRepoId("2-ROID")
                .build());

    registrant =
        persistResource(
            new Contact.Builder()
                .setContactId("5372808-ERL")
                .setRepoId("4-ROID")
                .setCreationRegistrarId("NewRegistrar")
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .setLocalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.LOCALIZED)
                        .setName("SHOULD NOT BE USED")
                        .setOrg("SHOULD NOT BE USED")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.INTERNATIONALIZED)
                        .setName("EXAMPLE REGISTRANT")
                        .setOrg("Tom & Jerry Corp.")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setVoiceNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.5555551212")
                        .setExtension("1234")
                        .build())
                .setFaxNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.5555551213")
                        .setExtension("4321")
                        .build())
                .setEmailAddress("EMAIL@EXAMPLE.tld")
                .build());

    adminContact =
        persistResource(
            new Contact.Builder()
                .setContactId("5372809-ERL")
                .setRepoId("5-ROID")
                .setCreationRegistrarId("NewRegistrar")
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .setLocalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.LOCALIZED)
                        .setName("SHOULD NOT BE USED")
                        .setOrg("SHOULD NOT BE USED")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.INTERNATIONALIZED)
                        .setName("EXAMPLE REGISTRANT ADMINISTRATIVE")
                        .setOrg("EXAMPLE REGISTRANT ORGANIZATION")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setVoiceNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.5555551212")
                        .setExtension("1234")
                        .build())
                .setFaxNumber(
                    new ContactPhoneNumber.Builder().setPhoneNumber("+1.5555551213").build())
                .setEmailAddress("EMAIL@EXAMPLE.tld")
                .build());

    techContact =
        persistResource(
            new Contact.Builder()
                .setContactId("5372811-ERL")
                .setRepoId("6-ROID")
                .setCreationRegistrarId("NewRegistrar")
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .setLocalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.LOCALIZED)
                        .setName("SHOULD NOT BE USED")
                        .setOrg("SHOULD NOT BE USED")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.INTERNATIONALIZED)
                        .setName("EXAMPLE REGISTRAR TECHNICAL")
                        .setOrg("EXAMPLE REGISTRAR LLC")
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("123 EXAMPLE STREET"))
                                .setCity("ANYTOWN")
                                .setState("AP")
                                .setZip("A1A1A1")
                                .setCountryCode("EX")
                                .build())
                        .build())
                .setVoiceNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.1235551234")
                        .setExtension("1234")
                        .build())
                .setFaxNumber(
                    new ContactPhoneNumber.Builder()
                        .setPhoneNumber("+1.5555551213")
                        .setExtension("93")
                        .build())
                .setEmailAddress("EMAIL@EXAMPLE.tld")
                .build());

    VKey<Host> host1VKey = host1.createVKey();
    VKey<Host> host2VKey = host2.createVKey();
    VKey<Contact> registrantResourceKey = registrant.createVKey();
    VKey<Contact> adminResourceKey = adminContact.createVKey();
    VKey<Contact> techResourceKey = techContact.createVKey();

    String repoId = "3-TLD";
    domain =
        persistResource(
            new Domain.Builder()
                .setDomainName("example.tld")
                .setRepoId(repoId)
                .setCreationRegistrarId("NewRegistrar")
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .setLastEppUpdateTime(DateTime.parse("2009-05-29T20:13:00Z"))
                .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
                .setRegistrationExpirationTime(DateTime.parse("2010-10-08T00:44:59Z"))
                .setStatusValues(
                    ImmutableSet.of(
                        StatusValue.CLIENT_DELETE_PROHIBITED,
                        StatusValue.CLIENT_RENEW_PROHIBITED,
                        StatusValue.CLIENT_TRANSFER_PROHIBITED,
                        StatusValue.SERVER_UPDATE_PROHIBITED))
                .setRegistrant(Optional.of(registrantResourceKey))
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey),
                        DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey)))
                .setNameservers(ImmutableSet.of(host1VKey, host2VKey))
                .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, "deadface")))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.create(
                            GracePeriodStatus.ADD, repoId, END_OF_TIME, "NewRegistrar", null),
                        GracePeriod.create(
                            GracePeriodStatus.TRANSFER, repoId, END_OF_TIME, "NewRegistrar", null)))
                .build());
  }

  @Test
  void getPlainTextOutputTest() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domain, false, "Please contact registrar", clock.nowUtc());
    assertThat(
            domainWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_domain.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_noRegistrant() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(
            domain.asBuilder().setRegistrant(Optional.empty()).build(),
            false,
            "Please contact registrar",
            clock.nowUtc());
    assertThat(
            domainWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_domain_no_registrant.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_noContacts() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(
            domain
                .asBuilder()
                .setRegistrant(Optional.empty())
                .setContacts(ImmutableSet.of())
                .build(),
            false,
            "Please contact registrar",
            clock.nowUtc());
    assertThat(
            domainWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_domain_no_contacts.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_registrarAbuseInfoMissing() {
    persistResource(abuseContact.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build());
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domain, false, "Please contact registrar", clock.nowUtc());
    assertThat(
        domainWhoisResponse.getResponse(false, "Footer"))
        .isEqualTo(
            WhoisResponseResults.create(
                loadFile("whois_domain_registrar_abuse_info_missing.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_fullOutput() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domain, true, "Please contact registrar", clock.nowUtc());
    assertThat(
            domainWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_domain_full_output.txt"), 1));
  }

  @Test
  void addImplicitOkStatusTest() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(
            domain.asBuilder().setStatusValues(null).build(),
            false,
            "Contact the registrar",
            clock.nowUtc());
    assertThat(
            domainWhoisResponse
                .getResponse(
                    false,
                    """
                        Doodle Disclaimer
                        I exist so that carriage return
                        in disclaimer can be tested.""")
                .plainTextOutput())
        .contains("Domain Status: ok");
  }
}
