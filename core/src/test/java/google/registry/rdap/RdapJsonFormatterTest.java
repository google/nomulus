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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapDataStructures.EventAction.TRANSFER;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHost;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomain;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.GsonSubject.assertAboutJson;
import static google.registry.testing.TestDataHelper.loadFile;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPocBase;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.ReplyPayloadBase;
import google.registry.rdap.RdapObjectClasses.TopLevelReplyObject;
import google.registry.testing.FakeClock;
import google.registry.testing.FullFieldsTestEntityHelper;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdapJsonFormatter}. */
class RdapJsonFormatterTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("1999-01-01T00:00:00Z"));

  private RdapJsonFormatter rdapJsonFormatter;

  private Registrar registrar;
  private Domain domainFull;
  private Domain domainNoNameserversNoTransfers;
  private Host hostIpv4;
  private Host hostIpv6;
  private Host hostBoth;
  private Host hostNoAddresses;
  private Host hostNotLinked;
  private Host hostSuperordinatePendingTransfer;
  @Nullable private Contact contactRegistrant;
  private Contact contactAdmin;
  private Contact contactTech;
  private Contact contactNotLinked;

  @BeforeEach
  void beforeEach() {
    rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter(clock);
    rdapJsonFormatter.rdapAuthorization =
        RdapAuthorization.create(RdapAuthorization.Role.REGISTRAR, "unicoderegistrar");

    // Create the registrar in 1999, then update it in 2000.
    clock.setTo(DateTime.parse("1999-01-01T00:00:00Z"));
    createTld("xn--q9jyb4c");
    registrar = persistResource(makeRegistrar("unicoderegistrar", "みんな", Registrar.State.ACTIVE));
    clock.setTo(DateTime.parse("2000-01-01T00:00:00Z"));
    registrar = persistResource(registrar);

    persistSimpleResources(makeMoreRegistrarContacts(registrar));

    contactRegistrant =
        FullFieldsTestEntityHelper.makeAndPersistContact(
            "8372808-ERL", "(◕‿◕)", "lol@cat.みんな", null, clock.nowUtc().minusYears(1), registrar);
    contactAdmin =
        FullFieldsTestEntityHelper.makeAndPersistContact(
            "8372808-IRL",
            "Santa Claus",
            null,
            ImmutableList.of("Santa Claus Tower", "41st floor", "Suite みんな"),
            clock.nowUtc().minusYears(2),
            registrar);
    contactTech =
        FullFieldsTestEntityHelper.makeAndPersistContact(
            "8372808-TRL",
            "The Raven",
            "bog@cat.みんな",
            ImmutableList.of("Chamber Door", "upper level"),
            clock.nowUtc().minusYears(3),
            registrar);
    contactNotLinked =
        FullFieldsTestEntityHelper.makeAndPersistContact(
            "8372808-QRL",
            "The Wizard",
            "dog@cat.みんな",
            ImmutableList.of("Somewhere", "Over the Rainbow"),
            clock.nowUtc().minusYears(4),
            registrar);
    hostIpv4 =
        makeAndPersistHost(
            "ns1.cat.みんな", "1.2.3.4", null, clock.nowUtc().minusYears(1), "unicoderegistrar");
    hostIpv6 =
        makeAndPersistHost(
            "ns2.cat.みんな",
            "bad:f00d:cafe:0:0:0:15:beef",
            null,
            clock.nowUtc().minusYears(2),
            "unicoderegistrar");
    hostBoth =
        makeAndPersistHost(
            "ns3.cat.みんな",
            "1.2.3.4",
            "bad:f00d:cafe:0:0:0:15:beef",
            clock.nowUtc().minusYears(3),
            "unicoderegistrar");
    hostNoAddresses =
        makeAndPersistHost(
            "ns4.cat.みんな", null, null, clock.nowUtc().minusYears(4), "unicoderegistrar");
    hostNotLinked =
        makeAndPersistHost(
            "ns5.cat.みんな", null, null, clock.nowUtc().minusYears(5), "unicoderegistrar");
    hostSuperordinatePendingTransfer =
        persistResource(
            makeAndPersistHost(
                    "ns1.dog.みんな", null, null, clock.nowUtc().minusYears(6), "unicoderegistrar")
                .asBuilder()
                .setSuperordinateDomain(
                    persistResource(
                            makeDomain(
                                    "dog.みんな",
                                    contactRegistrant,
                                    contactAdmin,
                                    contactTech,
                                    null,
                                    null,
                                    registrar)
                                .asBuilder()
                                .addStatusValue(StatusValue.PENDING_TRANSFER)
                                .setTransferData(
                                    new DomainTransferData.Builder()
                                        .setTransferStatus(TransferStatus.PENDING)
                                        .setGainingRegistrarId("NewRegistrar")
                                        .setTransferRequestTime(clock.nowUtc().minusDays(1))
                                        .setLosingRegistrarId("TheRegistrar")
                                        .setPendingTransferExpirationTime(
                                            clock.nowUtc().plusYears(100))
                                        .setTransferredRegistrationExpirationTime(
                                            DateTime.parse("2111-10-08T00:44:59Z"))
                                        .build())
                                .build())
                        .createVKey())
                .build());
    domainFull =
        persistResource(
            makeDomain(
                    "cat.みんな",
                    contactRegistrant,
                    contactAdmin,
                    contactTech,
                    hostIpv4,
                    hostIpv6,
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusMonths(4))
                .setLastEppUpdateTime(clock.nowUtc().minusMonths(3))
                .build());
    domainNoNameserversNoTransfers =
        persistResource(
            makeDomain(
                    "fish.みんな",
                    contactRegistrant,
                    contactRegistrant,
                    contactRegistrant,
                    null,
                    null,
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc())
                .setLastEppUpdateTime(null)
                .build());
    // Create an unused domain that references hostBoth and hostNoAddresses so that
    // they will have "associated" (ie, StatusValue.LINKED) status.
    persistResource(
        makeDomain(
            "dog.みんな",
            contactRegistrant,
            contactAdmin,
            contactTech,
            hostBoth,
            hostNoAddresses,
            registrar));

    // history entries
    // We create 3 "transfer approved" entries, to make sure we only save the last one
    persistResource(
        makeHistoryEntry(
            domainFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(3)));
    persistResource(
        makeHistoryEntry(
            domainFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(1)));
    persistResource(
        makeHistoryEntry(
            domainFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(2)));
    // We create a "transfer approved" entry for domainNoNameserversNoTransfers that happened
    // before the domain was created, to make sure we don't show it
    persistResource(
        makeHistoryEntry(
            domainNoNameserversNoTransfers,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(3)));
  }

  static ImmutableList<RegistrarPoc> makeMoreRegistrarContacts(Registrar registrar) {
    return ImmutableList.of(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Baby Doe")
            .setEmailAddress("babydoe@example.com")
            .setPhoneNumber("+1.2125551217")
            .setFaxNumber("+1.2125551218")
            .setTypes(ImmutableSet.of(RegistrarPocBase.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(false)
            .build(),
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("johndoe@example.com")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarPocBase.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(true)
            .build(),
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Jane Doe")
            .setEmailAddress("janedoe@example.com")
            .setPhoneNumber("+1.2125551215")
            .setTypes(ImmutableSet.of(RegistrarPocBase.Type.TECH, RegistrarPocBase.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .build(),
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Play Doe")
            .setEmailAddress("playdoe@example.com")
            .setPhoneNumber("+1.2125551217")
            .setFaxNumber("+1.2125551218")
            .setTypes(ImmutableSet.of(RegistrarPocBase.Type.BILLING))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .build());
  }

  private JsonObject loadJson(String expectedFileName) {
    return new Gson().fromJson(loadFile(getClass(), expectedFileName), JsonObject.class);
  }

  @Test
  void testRegistrar() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapRegistrarEntity(registrar, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_registrar.json"));
  }

  @Test
  void testRegistrar_summary() {
    assertAboutJson()
        .that(
            rdapJsonFormatter.createRdapRegistrarEntity(registrar, OutputDataType.SUMMARY).toJson())
        .isEqualTo(loadJson("rdapjson_registrar_summary.json"));
  }

  @Test
  void testHost_ipv4() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostIpv4, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_ipv4.json"));
  }

  @Test
  void testHost_ipv6() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostIpv6, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_ipv6.json"));
  }

  @Test
  void testHost_both() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostBoth, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_both.json"));
  }

  @Test
  void testHost_both_summary() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostBoth, OutputDataType.SUMMARY).toJson())
        .isEqualTo(loadJson("rdapjson_host_both_summary.json"));
  }

  @Test
  void testHost_noAddresses() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostNoAddresses, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_no_addresses.json"));
  }

  @Test
  void testHost_notLinked() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapNameserver(hostNotLinked, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_not_linked.json"));
  }

  @Test
  void testHost_superordinateHasPendingTransfer() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapNameserver(hostSuperordinatePendingTransfer, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_host_pending_transfer.json"));
  }

  @Test
  void testRegistrant() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant.json"));
  }

  @Test
  void testRegistrant_summary() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.SUMMARY)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant_summary.json"));
  }

  @Test
  void testRegistrant_loggedOut() {
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant_logged_out.json"));
  }

  @Test
  void testRegistrant_baseHasNoTrailingSlash() {
    // First, make sure we have a trailing slash at the end by default!
    // This test tries to change the default state, if the default doesn't have a /, then this test
    // doesn't help.
    assertThat(rdapJsonFormatter.fullServletPath).endsWith("/");
    rdapJsonFormatter.fullServletPath =
        rdapJsonFormatter.fullServletPath.substring(
            0, rdapJsonFormatter.fullServletPath.length() - 1);
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant.json"));
  }

  @Test
  void testAdmin() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactAdmin, ImmutableSet.of(RdapEntity.Role.ADMIN), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_admincontact.json"));
  }

  @Test
  void testTech() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactTech, ImmutableSet.of(RdapEntity.Role.TECH), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_techcontact.json"));
  }

  @Test
  void testRolelessContact() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(contactTech, ImmutableSet.of(), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_rolelesscontact.json"));
  }

  @Test
  void testUnlinkedContact() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapContactEntity(contactNotLinked, ImmutableSet.of(), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_unlinkedcontact.json"));
  }

  @Test
  void testDomain_full() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapDomain(domainFull, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_domain_full.json"));
  }

  @Test
  void testDomain_summary() {
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapDomain(domainFull, OutputDataType.SUMMARY).toJson())
        .isEqualTo(loadJson("rdapjson_domain_summary.json"));
  }

  @Test
  void testGetLastHistoryEntryByType() {
    // Expected data are from "rdapjson_domain_summary.json"
    assertThat(
            Maps.transformValues(
                rdapJsonFormatter.getLastHistoryEntryByType(domainFull),
                HistoryEntry::getModificationTime))
        .containsExactlyEntriesIn(
            ImmutableMap.of(TRANSFER, DateTime.parse("1999-12-01T00:00:00.000Z")));
  }

  @Test
  void testDomain_logged_out() {
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    assertAboutJson()
        .that(rdapJsonFormatter.createRdapDomain(domainFull, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_domain_logged_out.json"));
  }

  @Test
  void testDomain_noNameserversNoTransfersMultipleRoleContact() {
    assertAboutJson()
        .that(
            rdapJsonFormatter
                .createRdapDomain(domainNoNameserversNoTransfers, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_domain_no_nameservers.json"));
  }

  @Test
  void testError() {
    assertAboutJson()
        .that(
            RdapObjectClasses.ErrorResponse.create(
                    SC_BAD_REQUEST, "Invalid Domain Name", "Not a valid domain name")
                .toJson())
        .isEqualTo(loadJson("rdapjson_error.json"));
  }

  @Test
  void testTopLevel() {
    assertAboutJson()
        .that(
            TopLevelReplyObject.create(
                    new ReplyPayloadBase(BoilerplateType.OTHER) {
                      @JsonableElement public static final String key = "value";
                    },
                    rdapJsonFormatter.createTosNotice())
                .toJson())
        .isEqualTo(loadJson("rdapjson_toplevel.json"));
  }

  @Test
  void testTopLevel_domain() {
    assertAboutJson()
        .that(
            TopLevelReplyObject.create(
                    new ReplyPayloadBase(BoilerplateType.DOMAIN) {
                      @JsonableElement public static final String key = "value";
                    },
                    rdapJsonFormatter.createTosNotice())
                .toJson())
        .isEqualTo(loadJson("rdapjson_toplevel_domain.json"));
  }
}
