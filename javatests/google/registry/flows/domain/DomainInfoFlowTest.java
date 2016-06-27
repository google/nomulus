// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFileWithSubstitutions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;

import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceQueryFlow.ResourceToQueryDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineRule;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainInfoFlow}. */
public class DomainInfoFlowTest extends ResourceFlowTestCase<DomainInfoFlow, DomainResource> {

  private ContactResource registrant;
  private ContactResource contact;
  private HostResource host1;
  private HostResource host2;
  private HostResource host3;
  private DomainResource domain;

  @Before
  public void setup() {
    setEppInput("domain_info.xml");
    sessionMetadata.setClientId("NewRegistrar");
    clock.setTo(DateTime.parse("2005-03-03T22:00:00.000Z"));
    createTld("tld");
    persistResource(
        AppEngineRule.makeRegistrar1().asBuilder().setClientIdentifier("ClientZ").build());
  }

  private void persistTestEntities(String domainName, boolean inactive) {
    registrant = persistActiveContact("jd1234");
    contact = persistActiveContact("sh8013");
    host1 = persistActiveHost("ns1.example.tld");
    host2 = persistActiveHost("ns1.example.net");
    domain = persistResource(new DomainResource.Builder()
        .setFullyQualifiedDomainName(domainName)
        .setRepoId("2FF-TLD")
        .setCurrentSponsorClientId("NewRegistrar")
        .setCreationClientId("TheRegistrar")
        .setLastEppUpdateClientId("NewRegistrar")
        .setCreationTime(DateTime.parse("1999-04-03T22:00:00.0Z"))
        .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
        .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
        .setRegistrationExpirationTime(DateTime.parse("2005-04-03T22:00:00.0Z"))
        .setRegistrant(Ref.create(registrant))
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(Type.ADMIN, Ref.create(contact)),
            DesignatedContact.create(Type.TECH, Ref.create(contact))))
        .setNameservers(inactive ? null : ImmutableSet.of(Ref.create(host1), Ref.create(host2)))
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .build());
    // Set the superordinate domain of ns1.example.com to example.com. In reality, this would have
    // happened in the flow that created it, but here we just overwrite it in the datastore.
    host1 = persistResource(
        host1.asBuilder().setSuperordinateDomain(Ref.create(domain)).build());
    // Create a subordinate host that is not delegated to by anyone.
    host3 = persistResource(
        new HostResource.Builder()
            .setFullyQualifiedHostName("ns2.example.tld")
            .setRepoId("3FF-TLD")
            .setSuperordinateDomain(Ref.create(domain))
            .build());
    // Add the subordinate host references to the existing domain.
    domain = persistResource(domain.asBuilder()
        .setSubordinateHosts(ImmutableSet.of(
            host1.getFullyQualifiedHostName(),
            host3.getFullyQualifiedHostName()))
        .build());
  }

  private void persistTestEntities(boolean inactive) {
    persistTestEntities("example.tld", inactive);
  }

  private void doSuccessfulTest(String expectedXmlFilename, boolean inactive) throws Exception {
    assertTransactionalFlow(false);
    String expected = loadFileWithSubstitutions(
        getClass(), expectedXmlFilename, ImmutableMap.of("ROID", "2FF-TLD"));
    if (inactive) {
      expected = expected.replaceAll("\"ok\"", "\"inactive\"");
    }
    runFlowAssertResponse(expected);
    assertNoHistory();
    assertNoBillingEvents();
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    persistTestEntities(false);
    doSuccessfulTest(expectedXmlFilename, false);
  }

  private void doSuccessfulTestNoNameservers(String expectedXmlFilename) throws Exception {
    persistTestEntities(true);
    doSuccessfulTest(expectedXmlFilename, true);
  }

  @Test
  public void testSuccess_allHosts() throws Exception {
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  public void testSuccess_allHosts_noDelegatedHosts() throws Exception {
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  public void testSuccess_defaultHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  public void testSuccess_defaultHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  public void testSuccess_delegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    doSuccessfulTest("domain_info_response_delegated_hosts.xml");
  }

  @Test
  public void testSuccess_delegatedHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @Test
  public void testSuccess_subordinateHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTest("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  public void testSuccess_subordinateHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  public void testSuccess_noneHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTest("domain_info_response_none_hosts.xml");
  }

  @Test
  public void testSuccess_noneHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @Test
  public void testSuccess_unauthorized() throws Exception {
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response_unauthorized.xml");
  }

  @Test
  public void testSuccess_differentRegistrarWithAuthInfo() throws Exception {
    setEppInput("domain_info_with_auth.xml");
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  public void testSuccess_differentRegistrarWithRegistrantAuthInfo() throws Exception {
    persistTestEntities(false);
    setEppInput("domain_info_with_contact_auth.xml");
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml", false);
  }

  @Test
  public void testSuccess_differentRegistrarWithContactAuthInfo() throws Exception {
    persistTestEntities(false);
    setEppInput("domain_info_with_contact_auth.xml");
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    sessionMetadata.setClientId("ClientZ");
    doSuccessfulTest("domain_info_response.xml", false);
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    persistTestEntities(false);
    // Add the dsData to the saved resource and change the nameservers to match the sample xml.
    persistResource(domain.asBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(
            12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
        .setNameservers(ImmutableSet.of(Ref.create(host1), Ref.create(host3)))
        .build());
    doSuccessfulTest("domain_info_response_dsdata.xml", false);
  }

  private void doAddPeriodTest(GracePeriodStatus gracePeriodStatus) throws Exception {
    persistTestEntities(false);
    // Add the grace period to the saved resource, and change a few other fields to match the sample
    // xml.
    persistResource(domain.asBuilder()
        .addGracePeriod(
            GracePeriod.create(gracePeriodStatus, clock.nowUtc().plusDays(1), "foo", null))
        .setCreationClientId("NewRegistrar")
        .setCreationTime(DateTime.parse("2003-11-26T22:00:00.0Z"))
        .setRegistrationExpirationTime(DateTime.parse("2005-11-26T22:00:00.0Z"))
        .setLastTransferTime(null)
        .setLastEppUpdateTime(null)
        .setLastEppUpdateClientId(null)
        .build());
    doSuccessfulTest("domain_info_response_addperiod.xml", false);
  }

  @Test
  public void testSuccess_addGracePeriod() throws Exception {
    doAddPeriodTest(GracePeriodStatus.ADD);
  }

  @Test
  public void testSuccess_sunrushAddGracePeriod() throws Exception {
    doAddPeriodTest(GracePeriodStatus.SUNRUSH_ADD);
  }

  @Test
  public void testSuccess_autoRenewGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an AUTO_RENEW grace period to the saved resource.
    persistResource(domain.asBuilder()
        .addGracePeriod(GracePeriod.createForRecurring(
            GracePeriodStatus.AUTO_RENEW,
            clock.nowUtc().plusDays(1),
            "foo",
            Ref.create(Key.create(Recurring.class, 12345))))
        .build());
    doSuccessfulTest("domain_info_response_autorenewperiod.xml", false);
  }

  @Test
  public void testSuccess_redemptionGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an REDEMPTION grace period to the saved resource, and change a few other fields to match
    // the sample xml.
    persistResource(domain.asBuilder()
        .addGracePeriod(GracePeriod.create(
            GracePeriodStatus.REDEMPTION, clock.nowUtc().plusDays(1), "foo", null))
        .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
        .build());
    doSuccessfulTest("domain_info_response_redemptionperiod.xml", false);
  }

  @Test
  public void testSuccess_renewGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an RENEW grace period to the saved resource.
    persistResource(domain.asBuilder()
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.RENEW, clock.nowUtc().plusDays(1), "foo", null))
        .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @Test
  public void testSuccess_multipleRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add multiple RENEW grace periods to the saved resource.
    persistResource(domain.asBuilder()
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.RENEW, clock.nowUtc().plusDays(1), "foo", null))
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.RENEW, clock.nowUtc().plusDays(2), "foo", null))
        .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @Test
  public void testSuccess_transferGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an TRANSFER grace period to the saved resource.
    persistResource(domain.asBuilder()
        .addGracePeriod(GracePeriod.create(
            GracePeriodStatus.TRANSFER, clock.nowUtc().plusDays(1), "foo", null))
        .build());
    doSuccessfulTest("domain_info_response_transferperiod.xml", false);
  }

  @Test
  public void testSuccess_pendingDelete() throws Exception {
    persistTestEntities(false);
    // Set the domain to be pending delete with no grace period, which will cause an RGP status of
    // pending delete to show up, too.
    persistResource(domain.asBuilder()
        .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
        .build());
    doSuccessfulTest("domain_info_response_pendingdelete.xml", false);
  }

  @Test
  public void testSuccess_stackedAddRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add both an ADD and RENEW grace period, both which should show up in the RGP status.
    persistResource(domain.asBuilder()
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.ADD, clock.nowUtc().plusDays(1), "foo", null))
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.RENEW, clock.nowUtc().plusDays(2), "foo", null))
        .build());
    doSuccessfulTest("domain_info_response_stackedaddrenewperiod.xml", false);
  }

  @Test
  public void testSuccess_secDnsAndAddGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add both an ADD grace period and SecDNS data.
    persistResource(domain.asBuilder()
        .addGracePeriod(
            GracePeriod.create(GracePeriodStatus.ADD, clock.nowUtc().plusDays(1), "foo", null))
        .setDsData(ImmutableSet.of(DelegationSignerData.create(
            12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
        .build());
    doSuccessfulTest("domain_info_response_dsdata_addperiod.xml", false);
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    thrown.expect(
        ResourceToQueryDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    thrown.expect(
        ResourceToQueryDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    persistResource(newDomainResource("example.tld").asBuilder()
        .setDeletionTime(clock.nowUtc().minusDays(1))
        .build());
    runFlow();
  }

  @Test
  public void testFailure_differentRegistrarWrongAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(domain.asBuilder()
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
        .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_auth.xml");
    runFlow();
  }

  @Test
  public void testFailure_wrongAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(domain.asBuilder()
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
        .build());
    setEppInput("domain_info_with_auth.xml");
    runFlow();
  }

  @Test
  public void testFailure_differentRegistrarWrongRegistrantAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the registrant so that it does not match the file.
    registrant = persistResource(
        registrant.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our registrant.
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    runFlow();
  }

  @Test
  public void testFailure_wrongRegistrantAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the registrant so that it does not match the file.
    registrant = persistResource(
        registrant.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our registrant.
    eppLoader.replaceAll("JD1234-REP", registrant.getRepoId());
    runFlow();
  }

  @Test
  public void testFailure_differentRegistrarWrongContactAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the contact so that it does not match the file.
    contact = persistResource(
        contact.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our contact.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    runFlow();
  }

  @Test
  public void testFailure_wrongContactAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    // Change the password of the contact so that it does not match the file.
    contact = persistResource(
        contact.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our contact.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    runFlow();
  }

  @Test
  public void testFailure_differentRegistrarUnrelatedContactAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    ContactResource unrelatedContact = persistActiveContact("foo1234");
    sessionMetadata.setClientId("ClientZ");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our unrelated contact.
    eppLoader.replaceAll("JD1234-REP", unrelatedContact.getRepoId());
    runFlow();
  }

  @Test
  public void testFailure_unrelatedContactAuthInfo() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    persistTestEntities(false);
    ContactResource unrelatedContact = persistActiveContact("foo1234");
    setEppInput("domain_info_with_contact_auth.xml");
    // Replace the ROID in the xml file with the one for our unrelated contact.
    eppLoader.replaceAll("JD1234-REP", unrelatedContact.getRepoId());
    runFlow();
  }

  /** Test create command. */
  @Test
  public void testFeeExtension_createCommand() throws Exception {
    setEppInput("domain_info_fee_create.xml");
    persistTestEntities(false);
    doSuccessfulTest("domain_info_fee_create_response.xml", false);
  }

  /** Test renew command. */
  @Test
  public void testFeeExtension_renewCommand() throws Exception {
    setEppInput("domain_info_fee_renew.xml");
    persistTestEntities(false);
    doSuccessfulTest("domain_info_fee_renew_response.xml", false);
  }

  /** Test transfer command. */
  @Test
  public void testFeeExtension_transferCommand() throws Exception {
    setEppInput("domain_info_fee_transfer.xml");
    persistTestEntities(false);
    doSuccessfulTest("domain_info_fee_transfer_response.xml", false);
  }

  /** Test restore command. */
  @Test
  public void testFeeExtension_restoreCommand() throws Exception {
    setEppInput("domain_info_fee_restore.xml");
    persistTestEntities(false);
    doSuccessfulTest("domain_info_fee_restore_response.xml", false);
  }

  /** Test create command on a premium label. */
  @Test
  public void testFeeExtension_createCommandPremium() throws Exception {
    createTld("example");
    setEppInput("domain_info_fee_create_premium.xml");
    persistTestEntities("rich.example", false);
    doSuccessfulTest("domain_info_fee_create_premium_response.xml", false);
  }

  /** Test renew command on a premium label. */
  @Test
  public void testFeeExtension_renewCommandPremium() throws Exception {
    createTld("example");
    setEppInput("domain_info_fee_renew_premium.xml");
    persistTestEntities("rich.example", false);
    doSuccessfulTest("domain_info_fee_renew_premium_response.xml", false);
  }

  /** Test transfer command on a premium label. */
  @Test
  public void testFeeExtension_transferCommandPremium() throws Exception {
    createTld("example");
    setEppInput("domain_info_fee_transfer_premium.xml");
    persistTestEntities("rich.example", false);
    doSuccessfulTest("domain_info_fee_transfer_premium_response.xml", false);
  }

  /** Test restore command on a premium label. */
  @Test
  public void testFeeExtension_restoreCommandPremium() throws Exception {
    createTld("example");
    setEppInput("domain_info_fee_restore_premium.xml");
    persistTestEntities("rich.example", false);
    doSuccessfulTest("domain_info_fee_restore_premium_response.xml", false);
  }

  /** Test setting the currency explicitly to a wrong value. */
  @Test
  public void testFeeExtension_wrongCurrency() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    setEppInput("domain_info_fee_create_euro.xml");
    persistTestEntities(false);
    runFlow();
  }

  /** Test requesting a period that isn't in years. */
  @Test
  public void testFeeExtension_periodNotInYears() throws Exception {
    thrown.expect(BadPeriodUnitException.class);
    setEppInput("domain_info_fee_bad_period.xml");
    persistTestEntities(false);
    runFlow();
  }

  /** Test a command that specifies a phase. */
  @Test
  public void testFeeExtension_commandPhase() throws Exception {
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    setEppInput("domain_info_fee_command_phase.xml");
    persistTestEntities(false);
    runFlow();
  }

  /** Test a command that specifies a subphase. */
  @Test
  public void testFeeExtension_commandSubphase() throws Exception {
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    setEppInput("domain_info_fee_command_subphase.xml");
    persistTestEntities(false);
    runFlow();
  }

  /** Test a restore for more than one year. */
  @Test
  public void testFeeExtension_multiyearRestore() throws Exception {
    thrown.expect(RestoresAreAlwaysForOneYearException.class);
    setEppInput("domain_info_fee_multiyear_restore.xml");
    persistTestEntities(false);
    runFlow();
  }
}
