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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.whois.WhoisTestData.loadFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainWhoisResponse}. */
class DomainWhoisResponseTest {

  @RegisterExtension
  final AppEngineExtension gae = AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private HostResource hostResource1;
  private HostResource hostResource2;
  private RegistrarContact abuseContact;
  private ContactResource adminContact;
  private ContactResource registrant;
  private ContactResource techContact;
  private DomainBase domainBase;

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

    abuseContact = persistResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Jake Doe")
            .setEmailAddress("jakedoe@theregistrar.com")
            .setPhoneNumber("+1.2125551216")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());

    createTld("tld");

    hostResource1 =
        persistResource(
            new HostResource.Builder()
                .setHostName("ns01.exampleregistrar.tld")
                .setRepoId("1-ROID")
                .build());

    hostResource2 =
        persistResource(
            new HostResource.Builder()
                .setHostName("ns02.exampleregistrar.tld")
                .setRepoId("2-ROID")
                .build());

    registrant = persistResource(new ContactResource.Builder()
        .setContactId("5372808-ERL")
        .setRepoId("4-ROID")
        .setLocalizedPostalInfo(
            new PostalInfo.Builder()
            .setType(PostalInfo.Type.LOCALIZED)
            .setName("SHOULD NOT BE USED")
            .setOrg("SHOULD NOT BE USED")
            .setAddress(new ContactAddress.Builder()
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
            .setAddress(new ContactAddress.Builder()
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

    adminContact = persistResource(new ContactResource.Builder()
        .setContactId("5372809-ERL")
        .setRepoId("5-ROID")
        .setLocalizedPostalInfo(
            new PostalInfo.Builder()
            .setType(PostalInfo.Type.LOCALIZED)
            .setName("SHOULD NOT BE USED")
            .setOrg("SHOULD NOT BE USED")
            .setAddress(new ContactAddress.Builder()
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
            .setAddress(new ContactAddress.Builder()
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
            .build())
        .setEmailAddress("EMAIL@EXAMPLE.tld")
        .build());

    techContact = persistResource(new ContactResource.Builder()
        .setContactId("5372811-ERL")
        .setRepoId("6-ROID")
        .setLocalizedPostalInfo(
            new PostalInfo.Builder()
            .setType(PostalInfo.Type.LOCALIZED)
            .setName("SHOULD NOT BE USED")
            .setOrg("SHOULD NOT BE USED")
            .setAddress(new ContactAddress.Builder()
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
            .setAddress(new ContactAddress.Builder()
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

    VKey<HostResource> hostResource1Key = hostResource1.createVKey();
    VKey<HostResource> hostResource2Key = hostResource2.createVKey();
    VKey<ContactResource> registrantResourceKey = registrant.createVKey();
    VKey<ContactResource> adminResourceKey = adminContact.createVKey();
    VKey<ContactResource> techResourceKey = techContact.createVKey();

    String repoId = "3-TLD";
    domainBase =
        persistResource(
            new DomainBase.Builder()
                .setDomainName("example.tld")
                .setRepoId(repoId)
                .setLastEppUpdateTime(DateTime.parse("2009-05-29T20:13:00Z"))
                .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
                .setRegistrationExpirationTime(DateTime.parse("2010-10-08T00:44:59Z"))
                .setPersistedCurrentSponsorClientId("NewRegistrar")
                .setStatusValues(
                    ImmutableSet.of(
                        StatusValue.CLIENT_DELETE_PROHIBITED,
                        StatusValue.CLIENT_RENEW_PROHIBITED,
                        StatusValue.CLIENT_TRANSFER_PROHIBITED,
                        StatusValue.SERVER_UPDATE_PROHIBITED))
                .setRegistrant(registrantResourceKey)
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey),
                        DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey)))
                .setNameservers(ImmutableSet.of(hostResource1Key, hostResource2Key))
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, "deadface")))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.create(GracePeriodStatus.ADD, repoId, END_OF_TIME, "", null),
                        GracePeriod.create(
                            GracePeriodStatus.TRANSFER, repoId, END_OF_TIME, "", null)))
                .build());
  }

  @Test
  void getPlainTextOutputTest() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domainBase, false, "Please contact registrar", clock.nowUtc());
    assertThat(
            domainWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_domain.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_registrarAbuseInfoMissing() {
    persistResource(abuseContact.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build());
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domainBase, false, "Please contact registrar", clock.nowUtc());
    assertThat(
        domainWhoisResponse.getResponse(false, "Footer"))
        .isEqualTo(
            WhoisResponseResults.create(
                loadFile("whois_domain_registrar_abuse_info_missing.txt"), 1));
  }

  @Test
  void getPlainTextOutputTest_fullOutput() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domainBase, true, "Please contact registrar", clock.nowUtc());
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
            domainBase.asBuilder().setStatusValues(null).build(),
            false,
            "Contact the registrar",
            clock.nowUtc());
    assertThat(
            domainWhoisResponse
                .getResponse(
                    false,
                    "Doodle Disclaimer\nI exist so that carriage return\n"
                        + "in disclaimer can be tested.")
                .plainTextOutput())
        .contains("Domain Status: ok");
  }
}
