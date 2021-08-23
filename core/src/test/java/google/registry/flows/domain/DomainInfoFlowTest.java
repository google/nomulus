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

package google.registry.flows.domain;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.tld.Registry.TldState.QUIET_PERIOD;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.TestDataHelper.updateSubstitutions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.RequestCapturingAsyncDatastoreService;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.SetClockExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainInfoFlow}. */
@DualDatabaseTest
class DomainInfoFlowTest extends ResourceFlowTestCase<DomainInfoFlow, DomainBase> {

  @Order(value = Order.DEFAULT - 3)
  @RegisterExtension
  final SetClockExtension setClockExtension =
      new SetClockExtension(clock, "2005-03-03T22:00:00.000Z");

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  /**
   * The domain_info_fee.xml default substitutions common to most tests.
   *
   * <p>It doesn't set a default value to the COMMAND and PERIOD keys, because they are different in
   * every test.
   */
  private static final ImmutableMap<String, String> SUBSTITUTION_BASE =
      ImmutableMap.of(
          "NAME", "example.tld",
          "CURRENCY", "USD",
          "UNIT", "y");

  private ContactResource registrant;
  private ContactResource contact;
  private HostResource host1;
  private HostResource host2;
  private HostResource host3;
  private DomainBase domain;

  @BeforeEach
  void setup() {
    setEppInput("domain_info.xml");
    sessionMetadata.setClientId("NewRegistrar");
    createTld("tld");
    persistResource(AppEngineExtension.makeRegistrar1().asBuilder().setClientId("ClientZ").build());
  }

  private void persistTestEntities(String domainName, boolean inactive) {
    registrant = persistActiveContact("jd1234");
    contact = persistActiveContact("sh8013");
    host1 = persistActiveHost("ns1.example.tld");
    host2 = persistActiveHost("ns1.example.net");
    domain =
        persistResource(
            new DomainBase.Builder()
                .setDomainName(domainName)
                .setRepoId("2FF-TLD")
                .setPersistedCurrentSponsorClientId("NewRegistrar")
                .setCreationClientId("TheRegistrar")
                .setLastEppUpdateClientId("NewRegistrar")
                .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
                .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
                .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
                .setRegistrationExpirationTime(DateTime.parse("2005-04-03T22:00:00.0Z"))
                .setRegistrant(registrant.createVKey())
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.ADMIN, contact.createVKey()),
                        DesignatedContact.create(Type.TECH, contact.createVKey())))
                .setNameservers(
                    inactive ? null : ImmutableSet.of(host1.createVKey(), host2.createVKey()))
                .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
                .build());
    // Set the superordinate domain of ns1.example.com to example.com. In reality, this would have
    // happened in the flow that created it, but here we just overwrite it in Datastore.
    host1 = persistResource(host1.asBuilder().setSuperordinateDomain(domain.createVKey()).build());
    // Create a subordinate host that is not delegated to by anyone.
    host3 =
        persistResource(
            new HostResource.Builder()
                .setHostName("ns2.example.tld")
                .setRepoId("3FF-TLD")
                .setSuperordinateDomain(domain.createVKey())
                .build());
    // Add the subordinate host keys to the existing domain.
    domain =
        persistResource(
            domain
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(host1.getHostName(), host3.getHostName()))
                .build());
  }

  private void persistTestEntities(boolean inactive) {
    persistTestEntities("example.tld", inactive);
  }

  private void doSuccessfulTest(
      String expectedXmlFilename,
      boolean inactive,
      ImmutableMap<String, String> substitutions,
      boolean expectHistoryAndBilling)
      throws Exception {
    assertTransactionalFlow(false);
    String expected =
        loadFile(expectedXmlFilename, updateSubstitutions(substitutions, "ROID", "2FF-TLD"));
    if (inactive) {
      expected = expected.replaceAll("\"ok\"", "\"inactive\"");
    }
    runFlowAssertResponse(expected);
    if (!expectHistoryAndBilling) {
      assertNoHistory();
      assertNoBillingEvents();
    }
  }

  private void doSuccessfulTest(
      String expectedXmlFilename, boolean inactive, ImmutableMap<String, String> substitutions)
      throws Exception {
    doSuccessfulTest(expectedXmlFilename, inactive, substitutions, false);
  }

  private void doSuccessfulTest(String expectedXmlFilename, boolean inactive) throws Exception {
    doSuccessfulTest(expectedXmlFilename, inactive, ImmutableMap.of(), false);
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    persistTestEntities(false);
    doSuccessfulTest(expectedXmlFilename, false);
  }

  private void doSuccessfulTestNoNameservers(String expectedXmlFilename) throws Exception {
    persistTestEntities(true);
    doSuccessfulTest(expectedXmlFilename, true);
  }

  @TestOfyAndSql
  void testSuccess_allHosts() throws Exception {
    doSuccessfulTest("domain_info_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_info_no_cltrid.xml");
    doSuccessfulTest("domain_info_response_no_cltrid.xml");
  }

  @TestOfyAndSql
  void testSuccess_allHosts_noDelegatedHosts() throws Exception {
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_defaultHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    doSuccessfulTest("domain_info_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_defaultHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_delegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    doSuccessfulTest("domain_info_response_delegated_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_delegatedHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_subordinateHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTest("domain_info_response_subordinate_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_subordinateHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_noneHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTest("domain_info_response_none_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_noneHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @TestOfyAndSql
  void testSuccess_unauthorized() throws Exception {
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response_unauthorized.xml");
  }

  @TestOfyAndSql
  void testSuccess_differentRegistrarWithAuthInfo() throws Exception {
    setEppInput("domain_info_with_auth.xml");
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_differentRegistrarWithRegistrantAuthInfo() throws Exception {
    persistTestEntities(false);
    setEppInput("domain_info_with_contact_auth.xml");
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_differentRegistrarWithContactAuthInfo() throws Exception {
    persistTestEntities(false);
    setEppInput("domain_info_with_contact_auth.xml");
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_inQuietPeriod() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    doSuccessfulTest("domain_info_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_secDns() throws Exception {
    persistTestEntities(false);
    // Add the dsData to the saved resource and change the nameservers to match the sample xml.
    persistResource(
        domain
            .asBuilder()
            .setDsData(
                ImmutableSet.of(
                    DelegationSignerData.create(
                        12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
            .setNameservers(ImmutableSet.of(host1.createVKey(), host3.createVKey()))
            .build());
    doSuccessfulTest("domain_info_response_dsdata.xml", false);
  }

  private void doAddPeriodTest(GracePeriodStatus gracePeriodStatus) throws Exception {
    persistTestEntities(false);
    // Add the grace period to the saved resource, and change a few other fields to match the sample
    // xml.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    gracePeriodStatus,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setCreationClientId("NewRegistrar")
            .setCreationTimeForTest(DateTime.parse("2003-11-26T22:00:00.0Z"))
            .setRegistrationExpirationTime(DateTime.parse("2005-11-26T22:00:00.0Z"))
            .setLastTransferTime(null)
            .setLastEppUpdateTime(null)
            .setLastEppUpdateClientId(null)
            .build());
    doSuccessfulTest("domain_info_response_addperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_addGracePeriod() throws Exception {
    doAddPeriodTest(GracePeriodStatus.ADD);
  }

  @TestOfyAndSql
  void testSuccess_autoRenewGracePeriod() throws Exception {
    persistTestEntities(false);
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(clock.nowUtc())
                .setClientId(domain.getCreationClientId())
                .build());
    BillingEvent.Recurring renewEvent =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(getUniqueIdFromCommand())
                .setClientId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setRecurrenceEndTime(END_OF_TIME)
                .setParent(historyEntry)
                .build());
    VKey<BillingEvent.Recurring> recurringVKey = renewEvent.createVKey();
    // Add an AUTO_RENEW grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.createForRecurring(
                    GracePeriodStatus.AUTO_RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    recurringVKey))
            .build());
    doSuccessfulTest("domain_info_response_autorenewperiod.xml", false, ImmutableMap.of(), true);
  }

  @TestOfyAndSql
  void testSuccess_redemptionGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an REDEMPTION grace period to the saved resource, and change a few other fields to match
    // the sample xml.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.REDEMPTION,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest("domain_info_response_redemptionperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_renewGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an RENEW grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_multipleRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add multiple RENEW grace periods to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(2),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_transferGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an TRANSFER grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_transferperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_pendingDelete() throws Exception {
    persistTestEntities(false);
    // Set the domain to be pending delete with no grace period, which will cause an RGP status of
    // pending delete to show up, too.
    persistResource(
        domain.asBuilder().setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE)).build());
    doSuccessfulTest("domain_info_response_pendingdelete.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_stackedAddRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add both an ADD and RENEW grace period, both which should show up in the RGP status.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(2),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_stackedaddrenewperiod.xml", false);
  }

  @TestOfyAndSql
  void testSuccess_secDnsAndAddGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add both an ADD grace period and SecDNS data.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setDsData(
                ImmutableSet.of(
                    DelegationSignerData.create(
                        12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
            .build());
    doSuccessfulTest("domain_info_response_dsdata_addperiod.xml", false);
  }

  @TestOfyAndSql
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() throws Exception {
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_differentRegistrarWrongAuthInfo() {
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_auth.xml");
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongAuthInfo() {
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    setEppInput("domain_info_with_auth.xml");
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_differentRegistrarWrongRegistrantAuthInfo() {
    persistTestEntities(false);
    // Change the password of the registrant so that it does not match the file.
    registrant =
        persistResource(
            registrant
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
                .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our registrant.
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongRegistrantAuthInfo() {
    persistTestEntities(false);
    // Change the password of the registrant so that it does not match the file.
    registrant =
        persistResource(
            registrant
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
                .build());
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our registrant.
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_differentRegistrarWrongContactAuthInfo() {
    persistTestEntities(false);
    // Change the password of the contact so that it does not match the file.
    contact =
        persistResource(
            contact
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
                .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our contact.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongContactAuthInfo() {
    persistTestEntities(false);
    // Change the password of the contact so that it does not match the file.
    contact =
        persistResource(
            contact
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
                .build());
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our contact.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_differentRegistrarUnrelatedContactAuthInfo() {
    persistTestEntities(false);
    ContactResource unrelatedContact = persistActiveContact("foo1234");
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our unrelated contact.
    eppLoader.replaceAll("JD1234-REP", unrelatedContact.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unrelatedContactAuthInfo() {
    persistTestEntities(false);
    ContactResource unrelatedContact = persistActiveContact("foo1234");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our unrelated contact.
    eppLoader.replaceAll("JD1234-REP", unrelatedContact.getRepoId());
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /**
   * Test create command. Fee extension version 6 is the only one which supports fee extensions on
   * info commands and responses, so we don't need to test the other versions.
   */
  @TestOfyAndSql
  void testFeeExtension_createCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "create",
            "PERIOD", "2"));
    persistTestEntities(false);
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "create",
            "DESCRIPTION", "create",
            "PERIOD", "2",
            "FEE", "26.00"));
  }

  /** Test renew command. */
  @TestOfyAndSql
  void testFeeExtension_renewCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "renew",
            "PERIOD", "2"));
    persistTestEntities(false);
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "renew",
            "DESCRIPTION", "renew",
            "PERIOD", "2",
            "FEE", "22.00"));
  }

  /** Test transfer command. */
  @TestOfyAndSql
  void testFeeExtension_transferCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "transfer",
            "PERIOD", "1"));
    persistTestEntities(false);
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "transfer",
            "DESCRIPTION", "renew",
            "PERIOD", "1",
            "FEE", "11.00"));
  }

  /** Test restore command. */
  @TestOfyAndSql
  void testFeeExtension_restoreCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "restore",
            "PERIOD", "1"));
    persistTestEntities(false);
    doSuccessfulTest("domain_info_fee_restore_response.xml", false);
  }

  @TestOfyAndSql
  void testFeeExtension_restoreCommand_pendingDelete_noRenewal() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities(false);
    persistResource(
        domain
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest("domain_info_fee_restore_response_no_renewal.xml", false);
  }

  @TestOfyAndSql
  void testFeeExtension_restoreCommand_pendingDelete_withRenewal() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    persistResource(
        domain
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest("domain_info_fee_restore_response_with_renewal.xml", false);
  }

  /** Test create command on a premium label. */
  @TestOfyAndSql
  void testFeeExtension_createCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "NAME", "rich.example",
            "COMMAND", "create",
            "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "create", "DESCRIPTION", "create"));
  }

  /** Test renew command on a premium label. */
  @TestOfyAndSql
  void testFeeExtension_renewCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "NAME", "rich.example",
            "COMMAND", "renew",
            "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew"));
  }

  /** Test transfer command on a premium label. */
  @TestOfyAndSql
  void testFeeExtension_transferCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "NAME", "rich.example",
            "COMMAND", "transfer",
            "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "transfer", "DESCRIPTION", "renew"));
  }

  /** Test restore command on a premium label. */
  @TestOfyAndSql
  void testFeeExtension_restoreCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "NAME", "rich.example",
            "COMMAND", "restore",
            "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    doSuccessfulTest("domain_info_fee_restore_premium_response.xml", false);
  }

  /** Test setting the currency explicitly to a wrong value. */
  @TestOfyAndSql
  void testFeeExtension_wrongCurrency() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "create",
            "CURRENCY", "EUR",
            "PERIOD", "1"));
    persistTestEntities(false);
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFeeExtension_unknownCurrency() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "create",
            "CURRENCY", "BAD",
            "PERIOD", "1"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test requesting a period that isn't in years. */
  @TestOfyAndSql
  void testFeeExtension_periodNotInYears() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "create",
            "PERIOD", "2",
            "UNIT", "m"));
    persistTestEntities(false);
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a command that specifies a phase. */
  @TestOfyAndSql
  void testFeeExtension_commandPhase() {
    setEppInput("domain_info_fee_command_phase.xml");
    persistTestEntities(false);
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a command that specifies a subphase. */
  @TestOfyAndSql
  void testFeeExtension_commandSubphase() {
    setEppInput("domain_info_fee_command_subphase.xml");
    persistTestEntities(false);
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a restore for more than one year. */
  @TestOfyAndSql
  void testFeeExtension_multiyearRestore() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "restore",
            "PERIOD", "2"));
    persistTestEntities(false);
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a transfer for more than one year. */
  @TestOfyAndSql
  void testFeeExtension_multiyearTransfer() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE,
            "COMMAND", "transfer",
            "PERIOD", "2"));
    persistTestEntities(false);
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test that we load contacts and hosts as a batch rather than individually. */
  @TestOfyOnly // batching only relevant for Datastore
  void testBatchLoadingOfReferences() throws Exception {
    persistTestEntities(false);
    // Clear out the session cache so that we count actual Datastore calls.
    tm().clearSessionCache();
    int numPreviousReads = RequestCapturingAsyncDatastoreService.getReads().size();
    doSuccessfulTest("domain_info_response.xml", false);
    // Get all of the keys loaded in the flow, with each distinct load() call as a list of keys.
    int numReadsWithContactsOrHosts =
        (int)
            RequestCapturingAsyncDatastoreService.getReads()
                .stream()
                .skip(numPreviousReads)
                .filter(
                    keys ->
                        keys.stream()
                            .map(com.google.appengine.api.datastore.Key::getKind)
                            .anyMatch(
                                Predicates.in(
                                    ImmutableSet.of(
                                        Key.getKind(ContactResource.class),
                                        Key.getKind(HostResource.class)))))
                .count();
    // Nameserver keys now get persisted twice (because they are stored in nsHostsVKeys), so we
    // check for two loads instead of 1.
    assertThat(numReadsWithContactsOrHosts).isEqualTo(2);
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistTestEntities(false);
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-info");
    assertTldsFieldLogged("tld");
  }
}
