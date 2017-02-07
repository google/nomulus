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
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.whois.WhoisHelper.loadWhoisTestFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DomainWhoisResponse}. */
@RunWith(JUnit4.class)
public class DomainWhoisResponseTest {

  @Rule
  public final AppEngineRule gae = AppEngineRule.builder()
      .withDatastore()
      .build();

  HostResource hostResource1;
  HostResource hostResource2;
  ContactResource registrant;
  ContactResource adminContact;
  ContactResource techContact;
  DomainResource domainResource;

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @Before
  public void setUp() {
    // Update the registrar to have an IANA ID.
    persistResource(
        Registrar.loadByClientId("NewRegistrar").asBuilder().setIanaIdentifier(5555555L).build());

    createTld("tld");

    hostResource1 = persistResource(new HostResource.Builder()
        .setFullyQualifiedHostName("ns01.exampleregistrar.tld")
        .setRepoId("1-TLD")
        .build());

    hostResource2 = persistResource(new HostResource.Builder()
        .setFullyQualifiedHostName("ns02.exampleregistrar.tld")
        .setRepoId("2-TLD")
        .build());

    registrant = persistResource(new ContactResource.Builder()
        .setContactId("5372808-ERL")
        .setRepoId("4-TLD")
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
            .setOrg("EXAMPLE ORGANIZATION")
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
        .setRepoId("5-TLD")
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
        .setRepoId("6-TLD")
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

    Key<HostResource> hostResource1Key = Key.create(hostResource1);
    Key<HostResource> hostResource2Key = Key.create(hostResource2);
    Key<ContactResource> registrantResourceKey = Key.create(registrant);
    Key<ContactResource> adminResourceKey = Key.create(adminContact);
    Key<ContactResource> techResourceKey = Key.create(techContact);

    domainResource = persistResource(new DomainResource.Builder()
        .setFullyQualifiedDomainName("example.tld")
        .setRepoId("3-TLD")
        .setLastEppUpdateTime(DateTime.parse("2009-05-29T20:13:00Z"))
        .setCreationTimeForTest(DateTime.parse("2000-10-08T00:45:00Z"))
        .setRegistrationExpirationTime(DateTime.parse("2010-10-08T00:44:59Z"))
        .setCurrentSponsorClientId("NewRegistrar")
        .setStatusValues(ImmutableSet.of(
            StatusValue.CLIENT_DELETE_PROHIBITED,
            StatusValue.CLIENT_RENEW_PROHIBITED,
            StatusValue.CLIENT_TRANSFER_PROHIBITED,
            StatusValue.SERVER_UPDATE_PROHIBITED))
        .setRegistrant(registrantResourceKey)
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(DesignatedContact.Type.ADMIN, adminResourceKey),
            DesignatedContact.create(DesignatedContact.Type.TECH, techResourceKey)))
        .setNameservers(ImmutableSet.of(hostResource1Key, hostResource2Key))
        .setDsData(ImmutableSet.of(new DelegationSignerData()))
        .setGracePeriods(ImmutableSet.of(
            GracePeriod.create(GracePeriodStatus.ADD, END_OF_TIME, "", null),
            GracePeriod.create(GracePeriodStatus.TRANSFER, END_OF_TIME, "", null)))
        .build());
  }

  @Test
  public void getPlainTextOutputTest() {
    DomainWhoisResponse domainWhoisResponse =
        new DomainWhoisResponse(domainResource, clock.nowUtc());
    assertThat(domainWhoisResponse.getPlainTextOutput(false, "Doodle Disclaimer"))
        .isEqualTo(loadWhoisTestFile("whois_domain.txt"));
  }

  @Test
  public void addImplicitOkStatusTest() {
    DomainWhoisResponse domainWhoisResponse = new DomainWhoisResponse(
        domainResource.asBuilder().setStatusValues(null).build(),
        clock.nowUtc());
    assertThat(domainWhoisResponse.getPlainTextOutput(false, "Doodle Disclaimer"))
        .contains("Domain Status: ok");
  }
}
