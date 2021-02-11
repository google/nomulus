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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapTestHelper.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainBase;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.TestDataHelper.loadFile;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.ReplyPayloadBase;
import google.registry.rdap.RdapObjectClasses.TopLevelReplyObject;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdapJsonFormatter}. */
@DualDatabaseTest
class RdapJsonFormatterTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("1999-01-01T00:00:00Z"));

  private RdapJsonFormatter rdapJsonFormatter;

  private Registrar registrar;
  private DomainBase domainBaseFull;
  private DomainBase domainBaseNoNameserversNoTransfers;
  private HostResource hostResourceIpv4;
  private HostResource hostResourceIpv6;
  private HostResource hostResourceBoth;
  private HostResource hostResourceNoAddresses;
  private HostResource hostResourceNotLinked;
  private HostResource hostResourceSuperordinatePendingTransfer;
  private ContactResource contactResourceRegistrant;
  private ContactResource contactResourceAdmin;
  private ContactResource contactResourceTech;
  private ContactResource contactResourceNotLinked;

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);

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

    contactResourceRegistrant = makeAndPersistContactResource(
        "8372808-ERL",
        "(◕‿◕)",
        "lol@cat.みんな",
        null,
        clock.nowUtc().minusYears(1),
        registrar);
    contactResourceAdmin = makeAndPersistContactResource(
        "8372808-IRL",
        "Santa Claus",
        null,
        ImmutableList.of("Santa Claus Tower", "41st floor", "Suite みんな"),
        clock.nowUtc().minusYears(2),
        registrar);
    contactResourceTech = makeAndPersistContactResource(
        "8372808-TRL",
        "The Raven",
        "bog@cat.みんな",
        ImmutableList.of("Chamber Door", "upper level"),
        clock.nowUtc().minusYears(3),
        registrar);
    contactResourceNotLinked = makeAndPersistContactResource(
        "8372808-QRL",
        "The Wizard",
        "dog@cat.みんな",
        ImmutableList.of("Somewhere", "Over the Rainbow"),
        clock.nowUtc().minusYears(4),
        registrar);
    hostResourceIpv4 =
        makeAndPersistHostResource(
            "ns1.cat.みんな", "1.2.3.4", null, clock.nowUtc().minusYears(1), "unicoderegistrar");
    hostResourceIpv6 =
        makeAndPersistHostResource(
            "ns2.cat.みんな",
            "bad:f00d:cafe:0:0:0:15:beef",
            null,
            clock.nowUtc().minusYears(2),
            "unicoderegistrar");
    hostResourceBoth =
        makeAndPersistHostResource(
            "ns3.cat.みんな",
            "1.2.3.4",
            "bad:f00d:cafe:0:0:0:15:beef",
            clock.nowUtc().minusYears(3),
            "unicoderegistrar");
    hostResourceNoAddresses =
        makeAndPersistHostResource(
            "ns4.cat.みんな", null, null, clock.nowUtc().minusYears(4), "unicoderegistrar");
    hostResourceNotLinked =
        makeAndPersistHostResource(
            "ns5.cat.みんな", null, null, clock.nowUtc().minusYears(5), "unicoderegistrar");
    hostResourceSuperordinatePendingTransfer =
        persistResource(
            makeAndPersistHostResource(
                    "ns1.dog.みんな", null, null, clock.nowUtc().minusYears(6), "unicoderegistrar")
                .asBuilder()
                .setSuperordinateDomain(
                    persistResource(
                            makeDomainBase(
                                    "dog.みんな",
                                    contactResourceRegistrant,
                                    contactResourceAdmin,
                                    contactResourceTech,
                                    null,
                                    null,
                                    registrar)
                                .asBuilder()
                                .addStatusValue(StatusValue.PENDING_TRANSFER)
                                .setTransferData(
                                    new DomainTransferData.Builder()
                                        .setTransferStatus(TransferStatus.PENDING)
                                        .setGainingClientId("NewRegistrar")
                                        .setTransferRequestTime(clock.nowUtc().minusDays(1))
                                        .setLosingClientId("TheRegistrar")
                                        .setPendingTransferExpirationTime(
                                            clock.nowUtc().plusYears(100))
                                        .setTransferredRegistrationExpirationTime(
                                            DateTime.parse("2111-10-08T00:44:59Z"))
                                        .build())
                                .build())
                        .createVKey())
                .build());
    domainBaseFull =
        persistResource(
            makeDomainBase(
                    "cat.みんな",
                    contactResourceRegistrant,
                    contactResourceAdmin,
                    contactResourceTech,
                    hostResourceIpv4,
                    hostResourceIpv6,
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusMonths(4))
                .setLastEppUpdateTime(clock.nowUtc().minusMonths(3))
                .build());
    domainBaseNoNameserversNoTransfers =
        persistResource(
            makeDomainBase(
                    "fish.みんな",
                    contactResourceRegistrant,
                    contactResourceRegistrant,
                    contactResourceRegistrant,
                    null,
                    null,
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc())
                .setLastEppUpdateTime(null)
                .build());
    // Create an unused domain that references hostResourceBoth and hostResourceNoAddresses so that
    // they will have "associated" (ie, StatusValue.LINKED) status.
    persistResource(
        makeDomainBase(
            "dog.みんな",
            contactResourceRegistrant,
            contactResourceAdmin,
            contactResourceTech,
            hostResourceBoth,
            hostResourceNoAddresses,
            registrar));

    // history entries
    // We create 3 "transfer approved" entries, to make sure we only save the last one
    persistResource(
        makeHistoryEntry(
            domainBaseFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(3)));
    persistResource(
        makeHistoryEntry(
            domainBaseFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(1)));
    persistResource(
        makeHistoryEntry(
            domainBaseFull,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(2)));
    // We create a "transfer approved" entry for domainBaseNoNameserversNoTransfers that happened
    // before the domain was created, to make sure we don't show it
    persistResource(
        makeHistoryEntry(
            domainBaseNoNameserversNoTransfers,
            HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE,
            null,
            null,
            clock.nowUtc().minusMonths(3)));
  }

  static ImmutableList<RegistrarContact> makeMoreRegistrarContacts(Registrar registrar) {
    return ImmutableList.of(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Baby Doe")
            .setEmailAddress("babydoe@example.com")
            .setPhoneNumber("+1.2125551217")
            .setFaxNumber("+1.2125551218")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(false)
            .build(),
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("johndoe@example.com")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(false)
            .setVisibleInWhoisAsTech(true)
            .build(),
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Jane Doe")
            .setEmailAddress("janedoe@example.com")
            .setPhoneNumber("+1.2125551215")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH, RegistrarContact.Type.ADMIN))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .build(),
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Play Doe")
            .setEmailAddress("playdoe@example.com")
            .setPhoneNumber("+1.2125551217")
            .setFaxNumber("+1.2125551218")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.BILLING))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .build());
  }

  private JsonObject loadJson(String expectedFileName) {
    return new Gson().fromJson(loadFile(this.getClass(), expectedFileName), JsonObject.class);
  }

  @TestOfyAndSql
  void testRegistrar() {
    assertThat(rdapJsonFormatter.createRdapRegistrarEntity(registrar, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_registrar.json"));
  }

  @TestOfyAndSql
  void testRegistrar_summary() {
    assertThat(
            rdapJsonFormatter.createRdapRegistrarEntity(registrar, OutputDataType.SUMMARY).toJson())
        .isEqualTo(loadJson("rdapjson_registrar_summary.json"));
  }

  @TestOfyAndSql
  void testHost_ipv4() {
    assertThat(
            rdapJsonFormatter.createRdapNameserver(hostResourceIpv4, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_ipv4.json"));
  }

  @TestOfyAndSql
  void testHost_ipv6() {
    assertThat(
            rdapJsonFormatter.createRdapNameserver(hostResourceIpv6, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_ipv6.json"));
  }

  @TestOfyAndSql
  void testHost_both() {
    assertThat(
            rdapJsonFormatter.createRdapNameserver(hostResourceBoth, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_host_both.json"));
  }

  @TestOfyAndSql
  void testHost_both_summary() {
    assertThat(
            rdapJsonFormatter
                .createRdapNameserver(hostResourceBoth, OutputDataType.SUMMARY)
                .toJson())
        .isEqualTo(loadJson("rdapjson_host_both_summary.json"));
  }

  @TestOfyAndSql
  void testHost_noAddresses() {
    assertThat(
            rdapJsonFormatter
                .createRdapNameserver(hostResourceNoAddresses, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_host_no_addresses.json"));
  }

  @TestOfyAndSql
  void testHost_notLinked() {
    assertThat(
            rdapJsonFormatter
                .createRdapNameserver(hostResourceNotLinked, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_host_not_linked.json"));
  }

  @TestOfyAndSql
  void testHost_superordinateHasPendingTransfer() {
    assertThat(
            rdapJsonFormatter
                .createRdapNameserver(hostResourceSuperordinatePendingTransfer, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_host_pending_transfer.json"));
  }

  @TestOfyAndSql
  void testRegistrant() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant.json"));
  }

  @TestOfyAndSql
  void testRegistrant_summary() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.SUMMARY)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant_summary.json"));
  }

  @TestOfyAndSql
  void testRegistrant_loggedOut() {
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant_logged_out.json"));
  }

  @TestOfyAndSql
  void testRegistrant_baseHasNoTrailingSlash() {
    // First, make sure we have a trailing slash at the end by default!
    // This test tries to change the default state, if the default doesn't have a /, then this test
    // doesn't help.
    assertThat(rdapJsonFormatter.fullServletPath).endsWith("/");
    rdapJsonFormatter.fullServletPath =
        rdapJsonFormatter.fullServletPath.substring(
            0, rdapJsonFormatter.fullServletPath.length() - 1);
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceRegistrant,
                    ImmutableSet.of(RdapEntity.Role.REGISTRANT),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_registrant.json"));
  }

  @TestOfyAndSql
  void testAdmin() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceAdmin,
                    ImmutableSet.of(RdapEntity.Role.ADMIN),
                    OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_admincontact.json"));
  }

  @TestOfyAndSql
  void testTech() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceTech, ImmutableSet.of(RdapEntity.Role.TECH), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_techcontact.json"));
  }

  @TestOfyAndSql
  void testRolelessContact() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceTech, ImmutableSet.of(), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_rolelesscontact.json"));
  }

  @TestOfyAndSql
  void testUnlinkedContact() {
    assertThat(
            rdapJsonFormatter
                .createRdapContactEntity(
                    contactResourceNotLinked, ImmutableSet.of(), OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_unlinkedcontact.json"));
  }

  @TestOfyAndSql
  void testDomain_full() {
    assertThat(rdapJsonFormatter.createRdapDomain(domainBaseFull, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_domain_full.json"));
  }

  @TestOfyAndSql
  void testDomain_summary() {
    assertThat(rdapJsonFormatter.createRdapDomain(domainBaseFull, OutputDataType.SUMMARY).toJson())
        .isEqualTo(loadJson("rdapjson_domain_summary.json"));
  }

  @TestOfyAndSql
  void testDomain_logged_out() {
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    assertThat(rdapJsonFormatter.createRdapDomain(domainBaseFull, OutputDataType.FULL).toJson())
        .isEqualTo(loadJson("rdapjson_domain_logged_out.json"));
  }

  @TestOfyAndSql
  void testDomain_noNameserversNoTransfersMultipleRoleContact() {
    assertThat(
            rdapJsonFormatter
                .createRdapDomain(domainBaseNoNameserversNoTransfers, OutputDataType.FULL)
                .toJson())
        .isEqualTo(loadJson("rdapjson_domain_no_nameservers.json"));
  }

  @TestOfyAndSql
  void testError() {
    assertThat(
            RdapObjectClasses.ErrorResponse.create(
                    SC_BAD_REQUEST, "Invalid Domain Name", "Not a valid domain name")
                .toJson())
        .isEqualTo(loadJson("rdapjson_error.json"));
  }

  @TestOfyAndSql
  void testTopLevel() {
    assertThat(
            TopLevelReplyObject.create(
                    new ReplyPayloadBase(BoilerplateType.OTHER) {
                      @JsonableElement public String key = "value";
                    },
                    rdapJsonFormatter.createTosNotice())
                .toJson())
        .isEqualTo(loadJson("rdapjson_toplevel.json"));
  }

  @TestOfyAndSql
  void testTopLevel_domain() {
    assertThat(
            TopLevelReplyObject.create(
                    new ReplyPayloadBase(BoilerplateType.DOMAIN) {
                      @JsonableElement public String key = "value";
                    },
                    rdapJsonFormatter.createTosNotice())
                .toJson())
        .isEqualTo(loadJson("rdapjson_toplevel_domain.json"));
  }
}
