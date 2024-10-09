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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.union;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppcommon.StatusValue.CLIENT_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.CLIENT_HOLD;
import static google.registry.model.eppcommon.StatusValue.CLIENT_RENEW_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.CLIENT_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.CLIENT_UPDATE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.eppcommon.StatusValue.SERVER_RENEW_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_UPDATE;
import static google.registry.model.tld.Tld.TldState.QUIET_PERIOD;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.assertPollMessagesForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptySecDnsUpdateException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForNonFreeOperationException;
import google.registry.flows.domain.DomainFlowUtils.InvalidDsRecordException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MaxSigLifeChangeNotSupportedException;
import google.registry.flows.domain.DomainFlowUtils.MissingAdminContactException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.MissingTechnicalContactException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedForTldException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.SecDnsAllUsageException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.UrgentAttributeNotSupportedException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.Contact;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainUpdateFlow}. */
class DomainUpdateFlowTest extends ResourceFlowTestCase<DomainUpdateFlow, Domain> {

  private static final DomainDsData SOME_DSDATA =
      DomainDsData.create(
          1,
          2,
          2,
          base16().decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"));
  private static final ImmutableMap<String, String> OTHER_DSDATA_TEMPLATE_MAP =
      ImmutableMap.of(
          "KEY_TAG", "12346",
          "ALG", "3",
          "DIGEST_TYPE", "1",
          "DIGEST", "A94A8FE5CCB19BA61C4C0873D391E987982FBBD3");

  private Contact sh8013Contact;
  private Contact mak21Contact;
  private Contact unusedContact;
  private boolean cachedUseMinimumDataset;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    // Note that "domain_update.xml" tests adding and removing the same contact type.
    setEppInput("domain_update.xml");
    cachedUseMinimumDataset = RegistryConfig.useMinimumDataset();
  }

  @AfterEach
  void afterEach() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = cachedUseMinimumDataset;
  }

  private void persistReferencedEntities() {
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.foo", i));
    }
    sh8013Contact = persistActiveContact("sh8013");
    mak21Contact = persistActiveContact("mak21");
    unusedContact = persistActiveContact("unused");
  }

  private void persistDomainWithRegistrant() throws Exception {
    Host host = loadByForeignKey(Host.class, "ns1.example.foo", clock.nowUtc()).get();
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain(getUniqueIdFromCommand())
                .asBuilder()
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.TECH, mak21Contact.createVKey()),
                        DesignatedContact.create(Type.ADMIN, mak21Contact.createVKey()),
                        DesignatedContact.create(Type.BILLING, mak21Contact.createVKey())))
                .setRegistrant(Optional.of(mak21Contact.createVKey()))
                .setNameservers(ImmutableSet.of(host.createVKey()))
                .build());
    persistResource(
        new DomainHistory.Builder()
            .setType(DOMAIN_CREATE)
            .setModificationTime(clock.nowUtc())
            .setRegistrarId(domain.getCreationRegistrarId())
            .setDomain(domain)
            .build());
    clock.advanceOneMilli();
  }

  private Domain persistDomain() throws Exception {
    Host host = loadByForeignKey(Host.class, "ns1.example.foo", clock.nowUtc()).get();
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain(getUniqueIdFromCommand())
                .asBuilder()
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.TECH, sh8013Contact.createVKey()),
                        DesignatedContact.create(Type.ADMIN, unusedContact.createVKey())))
                .setNameservers(ImmutableSet.of(host.createVKey()))
                .build());
    persistResource(
        new DomainHistory.Builder()
            .setType(DOMAIN_CREATE)
            .setModificationTime(clock.nowUtc())
            .setRegistrarId(domain.getCreationRegistrarId())
            .setDomain(domain)
            .build());
    clock.advanceOneMilli();
    return domain;
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("generic_success_response.xml");
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));
    Domain domain = reloadResourceByForeignKey();
    // Check that the domain was updated. These values came from the xml.
    assertAboutDomains()
        .that(domain)
        .hasStatusValue(CLIENT_HOLD)
        .and()
        .hasAuthInfoPwd("2BARfoo")
        .and()
        .hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_UPDATE)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateRegistrarId("TheRegistrar")
        .and()
        .hasNoAutorenewEndTime();
    assertNoBillingEvents();
    assertDomainDnsRequests("example.tld");
    assertLastHistoryContainsResource(reloadResourceByForeignKey());
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    persistReferencedEntities();
    persistDomain();
    dryRunFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_update_no_cltrid.xml");
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest("generic_success_response_no_cltrid.xml");
  }

  @Test
  void testSuccess_noExistingAuthData() throws Exception {
    setEppInput("domain_update_no_auth_change.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create(null)))
            .build());
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Domain domain = reloadResourceByForeignKey();
    // Check that the domain was updated. These values came from the xml.
    assertAboutDomains()
        .that(domain)
        .hasStatusValue(CLIENT_HOLD)
        .and()
        .hasAuthInfoPwd(null)
        .and()
        .hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_UPDATE)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateRegistrarId("TheRegistrar")
        .and()
        .hasNoAutorenewEndTime();
    assertNoBillingEvents();
    assertDomainDnsRequests("example.tld");
    assertLastHistoryContainsResource(reloadResourceByForeignKey());
  }

  @Test
  void testSuccess_cachingDisabled() throws Exception {
    boolean origIsCachingEnabled = RegistryConfig.isEppResourceCachingEnabled();
    try {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(false);
      persistReferencedEntities();
      persistDomain();
      doSuccessfulTest();
    } finally {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(origIsCachingEnabled);
    }
  }

  @Test
  void testSuccess_inQuietPeriod() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
  }

  @Test
  void testFailure_thickRegistry_emptyRegistrant() throws Exception {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_update_empty_registrant.xml");
    persistReferencedEntities();
    persistDomain();
    MissingRegistrantException thrown =
        assertThrows(MissingRegistrantException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_emptyRegistrant() throws Exception {
    setEppInput("domain_update_empty_registrant.xml");
    persistReferencedEntities();
    persistDomain();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertThat(reloadResourceByForeignKey().getRegistrant()).isEmpty();
  }

  private void modifyDomainToHave13Nameservers() throws Exception {
    ImmutableSet.Builder<VKey<Host>> nameservers = new ImmutableSet.Builder<>();
    for (int i = 1; i < 15; i++) {
      if (i != 2) { // Skip 2 since that's the one that the tests will add.
        nameservers.add(
            loadByForeignKey(Host.class, String.format("ns%d.example.foo", i), clock.nowUtc())
                .get()
                .createVKey());
      }
    }
    persistResource(
        reloadResourceByForeignKey().asBuilder().setNameservers(nameservers.build()).build());
  }

  @Test
  void testSuccess_maxNumberOfNameservers() throws Exception {
    persistReferencedEntities();
    persistDomain();
    // Modify domain to have 13 nameservers. We will then remove one and add one in the test.
    modifyDomainToHave13Nameservers();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_addAndRemoveLargeNumberOfNameserversAndContacts() throws Exception {
    persistReferencedEntities();
    persistDomain();
    setEppInput("domain_update_max_everything.xml");
    // Create 26 hosts and 8 contacts. Start the domain with half of them.
    ImmutableSet.Builder<VKey<Host>> nameservers = new ImmutableSet.Builder<>();
    for (int i = 0; i < 26; i++) {
      Host host = persistActiveHost(String.format("max_test_%d.example.tld", i));
      if (i < 13) {
        nameservers.add(host.createVKey());
      }
    }
    ImmutableList.Builder<DesignatedContact> contactsBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 8; i++) {
      contactsBuilder.add(
          DesignatedContact.create(
              DesignatedContact.Type.values()[i % 4],
              persistActiveContact(String.format("max_test_%d", i)).createVKey()));
    }
    ImmutableList<DesignatedContact> contacts = contactsBuilder.build();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setNameservers(nameservers.build())
            .setContacts(ImmutableSet.copyOf(contacts.subList(0, 3)))
            .setRegistrant(Optional.of(contacts.get(3).getContactKey()))
            .build());
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Domain domain = reloadResourceByForeignKey();
    assertAboutDomains().that(domain).hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_UPDATE);
    assertThat(domain.getNameservers()).hasSize(13);
    // getContacts does not return contacts of type REGISTRANT, so check these separately.
    assertThat(domain.getContacts()).hasSize(3);
    assertThat(loadByKey(domain.getRegistrant().get()).getContactId()).isEqualTo("max_test_7");
    assertNoBillingEvents();
    assertDomainDnsRequests("example.tld");
  }

  @Test
  void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_update_metadata.xml");
    persistReferencedEntities();
    persistDomain();
    runFlow();
    Domain domain = reloadResourceByForeignKey();
    assertAboutDomains().that(domain).hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_UPDATE);
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, DOMAIN_UPDATE))
        .hasMetadataReason("domain-update-test")
        .and()
        .hasMetadataRequestedByRegistrar(true);
  }

  @Test
  void testSuccess_metadataNotFromTool() throws Exception {
    setEppInput("domain_update_metadata.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(OnlyToolCanPassMetadataException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_removeContact() throws Exception {
    setEppInput("domain_update_remove_contact.xml");
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_addAndRemoveSubordinateHostNameservers() throws Exception {
    // Test that operations involving subordinate hosts as nameservers do not change the subordinate
    // host relationship itself.
    setEppInput("domain_update_subordinate_hosts.xml");
    persistReferencedEntities();
    Domain domain = persistDomain();
    persistActiveSubordinateHost("ns1.example.tld", domain);
    Host addedHost = persistActiveSubordinateHost("ns2.example.tld", domain);
    persistResource(
        domain
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addSubordinateHost("ns2.example.tld")
            .setNameservers(
                ImmutableSet.of(
                    loadByForeignKey(Host.class, "ns1.example.tld", clock.nowUtc())
                        .get()
                        .createVKey()))
            .build());
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    domain = reloadResourceByForeignKey();
    assertThat(domain.getNameservers()).containsExactly(addedHost.createVKey());
    assertThat(domain.getSubordinateHosts()).containsExactly("ns1.example.tld", "ns2.example.tld");
    Host existingHost = loadByForeignKey(Host.class, "ns1.example.tld", clock.nowUtc()).get();
    addedHost = loadByForeignKey(Host.class, "ns2.example.tld", clock.nowUtc()).get();
    assertThat(existingHost.getSuperordinateDomain()).isEqualTo(domain.createVKey());
    assertThat(addedHost.getSuperordinateDomain()).isEqualTo(domain.createVKey());
  }

  @Test
  void testSuccess_registrantMovedToTechContact() throws Exception {
    setEppInput("domain_update_registrant_to_tech.xml");
    persistReferencedEntities();
    Contact sh8013 = loadByForeignKey(Contact.class, "sh8013", clock.nowUtc()).get();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrant(Optional.of(sh8013.createVKey()))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess_multipleReferencesToSameContactRemoved() throws Exception {
    setEppInput("domain_update_remove_multiple_contacts.xml");
    persistReferencedEntities();
    Contact sh8013 = loadByForeignKey(Contact.class, "sh8013", clock.nowUtc()).get();
    VKey<Contact> sh8013Key = sh8013.createVKey();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrant(Optional.of(sh8013Key))
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Key),
                    DesignatedContact.create(Type.BILLING, sh8013Key),
                    DesignatedContact.create(Type.TECH, sh8013Key)))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess_removeClientUpdateProhibited() throws Exception {
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setStatusValues(ImmutableSet.of(CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlow();
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .doesNotHaveStatusValue(CLIENT_UPDATE_PROHIBITED);
  }

  private void doSecDnsSuccessfulTest(
      String xmlFilename,
      ImmutableSet<DomainDsData> originalDsData,
      ImmutableSet<DomainDsData> expectedDsData,
      boolean dnsTaskEnqueued)
      throws Exception {
    doSecDnsSuccessfulTest(
        xmlFilename, originalDsData, expectedDsData, OTHER_DSDATA_TEMPLATE_MAP, dnsTaskEnqueued);
  }

  private void doSecDnsSuccessfulTest(
      String xmlFilename,
      ImmutableSet<DomainDsData> originalDsData,
      ImmutableSet<DomainDsData> expectedDsData,
      ImmutableMap<String, String> substitutions,
      boolean dnsTaskEnqueued)
      throws Exception {
    setEppInput(xmlFilename, substitutions);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(originalDsData)
            .build());
    assertMutatingFlow(true);
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Domain resource = reloadResourceByForeignKey();
    assertAboutDomains().that(resource).hasOnlyOneHistoryEntryWhich().hasType(DOMAIN_UPDATE);
    assertThat(resource.getDsData())
        .isEqualTo(
            expectedDsData.stream()
                .map(ds -> ds.cloneWithDomainRepoId(resource.getRepoId()))
                .collect(toImmutableSet()));
    if (dnsTaskEnqueued) {
      assertDomainDnsRequests("example.tld");
    } else {
      assertNoDnsRequests();
    }
  }

  @Test
  void testSuccess_secDnsAdd() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        null,
        ImmutableSet.of(
            DomainDsData.create(
                12346, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableMap.of(
            "KEY_TAG",
            "12346",
            "ALG",
            "3",
            "DIGEST_TYPE",
            "1",
            "DIGEST",
            "A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"),
        true);
  }

  @Test
  void testSuccess_secDnsAddPreservesExisting() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12346, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableMap.of(
            "KEY_TAG",
            "12346",
            "ALG",
            "3",
            "DIGEST_TYPE",
            "1",
            "DIGEST",
            "A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"),
        true);
  }

  @Test
  void testSuccess_secDnsAddSameDoesNothing() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA),
        ImmutableMap.of(
            "KEY_TAG",
            "1",
            "ALG",
            "2",
            "DIGEST_TYPE",
            "2",
            "DIGEST",
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"),
        false);
  }

  @Test
  void testSuccess_secDnsAddOnlyKeyTagRemainsSame() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                1,
                8,
                4,
                base16()
                    .decode(
                        "768412320F7B0AA5812FCE428DC4706B3CAE50E02A64CAA16A782249BFE8EFC4B7EF1CCB126255D196047DFEDF17A0A9"))),
        ImmutableMap.of(
            "KEY_TAG",
            "1",
            "ALG",
            "8",
            "DIGEST_TYPE",
            "4",
            "DIGEST",
            "768412320F7B0AA5812FCE428DC4706B3CAE50E02A64CAA16A782249BFE8EFC4B7EF1CCB126255D196047DFEDF17A0A9"),
        true);
  }

  // Changing any of the four fields in DomainDsData should result in a new object
  @Test
  void testSuccess_secDnsAddOnlyChangeKeyTag() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12346,
                2,
                2,
                base16()
                    .decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"))),
        ImmutableMap.of(
            "KEY_TAG",
            "12346",
            "ALG",
            "2",
            "DIGEST_TYPE",
            "2",
            "DIGEST",
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"),
        true);
  }

  @Test
  void testSuccess_secDnsAddOnlyChangeAlgorithm() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                1,
                8,
                2,
                base16()
                    .decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"))),
        ImmutableMap.of(
            "KEY_TAG",
            "1",
            "ALG",
            "8",
            "DIGEST_TYPE",
            "2",
            "DIGEST",
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"),
        true);
  }

  @Test
  void testSuccess_secDnsAddOnlyChangeDigestType() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                1,
                2,
                4,
                base16()
                    .decode(
                        "768412320F7B0AA5812FCE428DC4706B3CAE50E02A64CAA16A782249BFE8EFC4B7EF1CCB126255D196047DFEDF17A0A9"))),
        ImmutableMap.of(
            "KEY_TAG",
            "1",
            "ALG",
            "2",
            "DIGEST_TYPE",
            "4",
            "DIGEST",
            "768412320F7B0AA5812FCE428DC4706B3CAE50E02A64CAA16A782249BFE8EFC4B7EF1CCB126255D196047DFEDF17A0A9"),
        true);
  }

  @Test
  void testSuccess_secDnsAddOnlyChangeDigest() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                1,
                2,
                2,
                base16()
                    .decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"))),
        ImmutableMap.of(
            "KEY_TAG",
            "1",
            "ALG",
            "2",
            "DIGEST_TYPE",
            "2",
            "DIGEST",
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"),
        false);
  }

  @Test
  void testSuccess_secDnsAddToMaxRecords() throws Exception {
    ImmutableSet.Builder<DomainDsData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 7; ++i) {
      builder.add(
          DomainDsData.create(
              i,
              2,
              2,
              base16().decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08")));
    }
    ImmutableSet<DomainDsData> commonDsData = builder.build();

    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        commonDsData,
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DomainDsData.create(
                        12346,
                        3,
                        1,
                        base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))))),
        true);
  }

  @Test
  void testSuccess_secDnsRemove() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12346, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableSet.of(SOME_DSDATA),
        true);
  }

  @Test
  void testSuccess_secDnsRemoveAll() throws Exception {
    // As an aside, this test also validates that it's ok to set the 'urgent' attribute to false.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem_all.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12346, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableSet.of(),
        true);
  }

  @Test
  void testSuccess_secDnsAddRemove() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12345, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12346, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        true);
  }

  @Test
  void testSuccess_secDnsAddRemoveToMaxRecords() throws Exception {
    ImmutableSet.Builder<DomainDsData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 7; ++i) {
      builder.add(
          DomainDsData.create(
              i,
              2,
              2,
              base16().decode("9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08")));
    }
    ImmutableSet<DomainDsData> commonDsData = builder.build();

    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem.xml",
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DomainDsData.create(
                        12345,
                        3,
                        1,
                        base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))))),
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DomainDsData.create(
                        12346,
                        3,
                        1,
                        base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))))),
        true);
  }

  @Test
  void testSuccess_secDnsAddRemoveSame() throws Exception {
    // Adding and removing the same dsData is a no-op because removes are processed first.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem_same.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12345, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        ImmutableSet.of(
            SOME_DSDATA,
            DomainDsData.create(
                12345, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))),
        false);
  }

  @Test
  void testSuccess_secDnsRemoveAlreadyNotThere() throws Exception {
    // Removing a dsData that isn't there is a no-op.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA),
        false);
  }

  void doServerStatusBillingTest(String xmlFilename, boolean isBillable) throws Exception {
    setEppInput(xmlFilename);
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));

    if (isBillable) {
      assertBillingEvents(
          new BillingEvent.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId("example.tld")
              .setRegistrarId("TheRegistrar")
              .setCost(Money.of(USD, 19))
              .setEventTime(clock.nowUtc())
              .setBillingTime(clock.nowUtc())
              .setDomainHistory(
                  getOnlyHistoryEntryOfType(
                      reloadResourceByForeignKey(), DOMAIN_UPDATE, DomainHistory.class))
              .build());
    } else {
      assertNoBillingEvents();
    }
  }

  @Test
  void testSuccess_addServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    persistDomain();
    doServerStatusBillingTest("domain_update_add_server_status.xml", true);
  }

  @Test
  void testSuccess_noBillingOnPreExistingServerStatus() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    Domain addStatusDomain = persistActiveDomain(getUniqueIdFromCommand());
    persistResource(addStatusDomain.asBuilder().addStatusValue(SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_add_server_status.xml", false);
  }

  @Test
  void testSuccess_removeServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    Domain removeStatusDomain = persistDomain();
    persistResource(removeStatusDomain.asBuilder().addStatusValue(SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_remove_server_status.xml", true);
  }

  @Test
  void testSuccess_changeServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    Domain changeStatusDomain = persistDomain();
    persistResource(changeStatusDomain.asBuilder().addStatusValue(SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_change_server_status.xml", true);
  }

  @Test
  void testSuccess_noBillingEventOnNonServerStatusChange() throws Exception {
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_non_server_status.xml", false);
  }

  @Test
  void testSuccess_noBillingEventOnServerHoldStatusChange() throws Exception {
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_server_hold_status.xml", false);
  }

  @Test
  void testSuccess_noBillingEventOnServerStatusChangeNotFromRegistrar() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_server_status_non_registrar.xml", false);
  }

  @Test
  void testSuccess_superuserClientUpdateProhibited() throws Exception {
    setEppInput("domain_update_add_server_hold_status.xml");
    persistReferencedEntities();
    persistResource(
        persistActiveDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasStatusValue(CLIENT_UPDATE_PROHIBITED)
        .and()
        .hasStatusValue(SERVER_HOLD);
  }

  private void doSecDnsFailingTest(
      Class<? extends EppException> expectedException, String xmlFilename) throws Exception {
    setEppInput(xmlFilename);
    persistReferencedEntities();
    persistActiveDomain(getUniqueIdFromCommand());
    EppException thrown = assertThrows(expectedException, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsAllCannotBeFalse() throws Exception {
    doSecDnsFailingTest(SecDnsAllUsageException.class, "domain_update_dsdata_rem_all_false.xml");
  }

  @Test
  void testFailure_secDnsEmptyNotAllowed() throws Exception {
    doSecDnsFailingTest(EmptySecDnsUpdateException.class, "domain_update_dsdata_empty.xml");
  }

  @Test
  void testFailure_secDnsUrgentNotSupported() throws Exception {
    doSecDnsFailingTest(
        UrgentAttributeNotSupportedException.class, "domain_update_dsdata_urgent.xml");
  }

  @Test
  void testFailure_secDnsChangeNotSupported() throws Exception {
    doSecDnsFailingTest(
        MaxSigLifeChangeNotSupportedException.class, "domain_update_maxsiglife.xml");
  }

  @Test
  void testFailure_secDnsInvalidDigestType() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsMultipleInvalidDigestTypes() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(
                ImmutableSet.of(
                    DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2}),
                    DomainDsData.create(2, 2, 6, new byte[] {0, 1, 2})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("digestType=3");
    assertThat(thrown).hasMessageThat().contains("digestType=6");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsInvalidDigestLength() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 1, new byte[] {0, 1, 2})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain contains DS record(s) with an invalid digest length");
  }

  @Test
  void testFailure_secDnsMultipleInvalidDigestLengths() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(
                ImmutableSet.of(
                    DomainDsData.create(1, 2, 1, new byte[] {0, 1, 2, 3, 4}),
                    DomainDsData.create(2, 2, 2, new byte[] {5, 6, 7})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("0, 1, 2, 3, 4");
    assertThat(thrown).hasMessageThat().contains("5, 6, 7");
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain contains DS record(s) with an invalid digest length");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsInvalidAlgorithm() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 99, 2, new byte[] {0, 1, 2})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsMultipleInvalidAlgorithms() throws Exception {
    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(
                ImmutableSet.of(
                    DomainDsData.create(1, 998, 2, new byte[] {0, 1, 2}),
                    DomainDsData.create(2, 99, 2, new byte[] {0, 1, 2})))
            .build());
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("algorithm=998");
    assertThat(thrown).hasMessageThat().contains("algorithm=99");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsTooManyDsRecords() throws Exception {
    ImmutableSet.Builder<DomainDsData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 8; ++i) {
      builder.add(DomainDsData.create(i, 2, 2, new byte[] {0, 1, 2}));
    }

    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDsData(builder.build())
            .build());
    EppException thrown = assertThrows(TooManyDsRecordsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_tooManyNameservers() throws Exception {
    setEppInput("domain_update_add_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    // Modify domain so it has 13 nameservers. We will then try to add one in the test.
    modifyDomainToHave13Nameservers();
    EppException thrown = assertThrows(TooManyNameserversException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongExtension() throws Exception {
    setEppInput("domain_update_wrong_extension.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(UnimplementedExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    persistReferencedEntities();
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistReferencedEntities();
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_missingHost() throws Exception {
    persistActiveHost("ns1.example.foo");
    persistActiveContact("sh8013");
    persistActiveContact("mak21");
    persistActiveDomain(getUniqueIdFromCommand());
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns2.example.foo)");
  }

  @Test
  void testFailure_missingContact() throws Exception {
    persistActiveHost("ns1.example.foo");
    persistActiveHost("ns2.example.foo");
    persistActiveContact("mak21");
    persistActiveDomain(getUniqueIdFromCommand());
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(sh8013)");
  }

  @Test
  void testFailure_addingDuplicateContact() throws Exception {
    persistReferencedEntities();
    persistActiveContact("foo");
    persistDomain();
    // Add a tech contact to the persisted entity, which should cause the flow to fail when it tries
    // to add "mak21" as a second tech contact.
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setContacts(
                DesignatedContact.create(
                    Type.TECH,
                    loadByForeignKey(Contact.class, "foo", clock.nowUtc()).get().createVKey()))
            .build());
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertThat(thrown.getResult().getMsg())
        .isEqualTo(
            "More than one contact for a given role is not allowed: "
                + "role [tech] has contacts [foo, mak21]");
  }

  @Test
  void testFailure_statusValueNotClientSettable() throws Exception {
    setEppInput("domain_update_prohibited_status.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserCanSetServerStatusValues() throws Exception {
    setEppInput("domain_update_prohibited_status.xml");
    persistReferencedEntities();
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    // No poll message because the server status was added by the owning registrar.
    assertThat(getPollMessages()).isEmpty();
  }

  @Test
  void testSuccess_addingServerStatusValue_sendsPollMessage() throws Exception {
    setEppInput("domain_update_prohibited_status.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    Domain updatedDomain = reloadResourceByForeignKey();
    assertPollMessagesForResource(
        updatedDomain,
        new PollMessage.OneTime.Builder()
            .setEventTime(clock.nowUtc())
            .setHistoryEntry(getOnlyHistoryEntryOfType(updatedDomain, DOMAIN_UPDATE))
            .setRegistrarId("NewRegistrar")
            .setMsg("The registry administrator has added the status(es) [serverHold].")
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        "example.tld",
                        true,
                        Trid.create("ABC-12345", "server-trid"),
                        clock.nowUtc())))
            .build());
  }

  @Test
  void testSuccess_removingServerStatusValue_sendsPollMessage() throws Exception {
    setEppInput("domain_update_remove_server_statuses.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .setStatusValues(
                ImmutableSet.of(
                    SERVER_DELETE_PROHIBITED,
                    SERVER_TRANSFER_PROHIBITED,
                    SERVER_UPDATE_PROHIBITED,
                    CLIENT_DELETE_PROHIBITED,
                    CLIENT_RENEW_PROHIBITED,
                    CLIENT_HOLD,
                    SERVER_HOLD))
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    Domain updatedDomain = reloadResourceByForeignKey();
    assertPollMessagesForResource(
        updatedDomain,
        new PollMessage.OneTime.Builder()
            .setEventTime(clock.nowUtc())
            .setHistoryEntry(getOnlyHistoryEntryOfType(updatedDomain, DOMAIN_UPDATE))
            .setRegistrarId("NewRegistrar")
            .setMsg(
                "The registry administrator has removed the status(es) [serverHold,"
                    + " serverTransferProhibited, serverUpdateProhibited].")
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        "example.tld",
                        true,
                        Trid.create("ABC-12345", "server-trid"),
                        clock.nowUtc())))
            .build());
  }

  @Test
  void testSuccess_addingAndRemovingServerStatusValues_sendsPollMessage() throws Exception {
    setEppInput("domain_update_change_server_statuses.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .setStatusValues(
                ImmutableSet.of(
                    SERVER_DELETE_PROHIBITED,
                    SERVER_TRANSFER_PROHIBITED,
                    CLIENT_DELETE_PROHIBITED,
                    CLIENT_RENEW_PROHIBITED))
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    Domain updatedDomain = reloadResourceByForeignKey();
    assertPollMessagesForResource(
        updatedDomain,
        new PollMessage.OneTime.Builder()
            .setEventTime(clock.nowUtc())
            .setHistoryEntry(getOnlyHistoryEntryOfType(updatedDomain, DOMAIN_UPDATE))
            .setRegistrarId("NewRegistrar")
            .setMsg(
                "The registry administrator has added the status(es) [serverHold,"
                    + " serverRenewProhibited] and removed the status(es)"
                    + " [serverTransferProhibited].")
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        "example.tld",
                        true,
                        Trid.create("ABC-12345", "server-trid"),
                        clock.nowUtc())))
            .build());
  }

  @Test
  void testFailure_serverUpdateProhibited_prohibitsNonSuperuserUpdates() throws Exception {
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .addStatusValue(SERVER_UPDATE_PROHIBITED)
            .build());
    Exception e = assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(e).hasMessageThat().contains("serverUpdateProhibited");
  }

  @Test
  void testSuccess_serverUpdateProhibited_allowsSuperuserUpdates() throws Exception {
    persistReferencedEntities();
    persistResource(persistDomain().asBuilder().addStatusValue(SERVER_UPDATE_PROHIBITED).build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_serverUpdateProhibited_notSettableWithoutSuperuser() throws Exception {
    setEppInput("domain_update_add_registry_lock.xml");
    persistReferencedEntities();
    persistDomain();
    Exception e = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertThat(e).hasMessageThat().contains("serverUpdateProhibited");
  }

  @Test
  void testSuccess_serverUpdateProhibited_isSettableWithSuperuser() throws Exception {
    setEppInput("domain_update_add_registry_lock.xml");
    persistReferencedEntities();
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_clientUpdateProhibited() throws Exception {
    createTld("com");
    setEppInput("domain_update_authinfo.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(CLIENT_UPDATE_PROHIBITED))
            .build());
    EppException thrown =
        assertThrows(ResourceHasClientUpdateProhibitedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverUpdateProhibited() throws Exception {
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(SERVER_UPDATE_PROHIBITED))
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverUpdateProhibited");
  }

  @Test
  void testFailure_pendingDelete() throws Exception {
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(1))
            .addStatusValue(PENDING_DELETE)
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
  }

  @Test
  void testFailure_duplicateContactInCommand() throws Exception {
    setEppInput("domain_update_duplicate_contact.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_multipleDuplicateContactInCommand() throws Exception {
    setEppInput("domain_update_multiple_duplicate_contacts.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "More than one contact for a given role is not allowed: "
                + "role [billing] has contacts [mak21, sh8013], "
                + "role [tech] has contacts [mak21, sh8013]");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_missingContactType() throws Exception {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    setEppInput("domain_update_missing_contact_type.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(MissingContactTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistReferencedEntities();
    persistDomain();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_notAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistReferencedEntities();
    persistDomain();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_sameNameserverAddedAndRemoved() throws Exception {
    setEppInput("domain_update_add_remove_same_host.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setNameservers(
                ImmutableSet.of(
                    loadByForeignKey(Host.class, "ns1.example.foo", clock.nowUtc())
                        .get()
                        .createVKey()))
            .build());
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // Contacts mismatch.
  @Test
  void testFailure_sameContactAddedAndRemoved() throws Exception {
    setEppInput("domain_update_add_remove_same_contact.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                DesignatedContact.create(
                    Type.TECH,
                    loadByForeignKey(Contact.class, "sh8013", clock.nowUtc()).get().createVKey()))
            .build());
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_thickRegistry_removeAdmin() throws Exception {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_update_remove_admin.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Contact.createVKey()),
                    DesignatedContact.create(Type.TECH, sh8013Contact.createVKey())))
            .build());
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_removeAdmin() throws Exception {
    setEppInput("domain_update_remove_admin.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Contact.createVKey()),
                    DesignatedContact.create(Type.TECH, sh8013Contact.createVKey())))
            .build());
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_thickRegistry_removeTech() throws Exception {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_update_remove_tech.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Contact.createVKey()),
                    DesignatedContact.create(Type.TECH, sh8013Contact.createVKey())))
            .build());
    EppException thrown = assertThrows(MissingTechnicalContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_removeTech() throws Exception {
    setEppInput("domain_update_remove_tech.xml");
    persistReferencedEntities();
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Contact.createVKey()),
                    DesignatedContact.create(Type.TECH, sh8013Contact.createVKey())))
            .build());
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_addPendingDeleteContact() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        loadByForeignKey(Contact.class, "mak21", clock.nowUtc())
            .get()
            .asBuilder()
            .addStatusValue(PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("mak21");
  }

  @Test
  void testFailure_addPendingDeleteHost() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        loadByForeignKey(Host.class, "ns2.example.foo", clock.nowUtc())
            .get()
            .asBuilder()
            .addStatusValue(PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.foo");
  }

  @Test
  void testFailure_newRegistrantNotAllowListed() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("contact1234"))
            .build());
    clock.advanceOneMilli();
    EppException thrown = assertThrows(RegistrantNotAllowedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_addedNameserverDisallowedInTld() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    NameserversNotAllowedForTldException thrown =
        assertThrows(NameserversNotAllowedForTldException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.foo");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_newNameserverAllowListed() throws Exception {
    setEppInput("domain_update_add_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    // No registrant is given but both nameserver and registrant allow list exist.
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.foo", "ns2.example.foo"))
            .build());
    assertThat(reloadResourceByForeignKey().getNameservers())
        .doesNotContain(
            loadByForeignKey(Host.class, "ns2.example.foo", clock.nowUtc()).get().createVKey());
    runFlow();
    assertThat(reloadResourceByForeignKey().getNameservers())
        .contains(
            loadByForeignKey(Host.class, "ns2.example.foo", clock.nowUtc()).get().createVKey());
  }

  @Test
  void testSuccess_changeRegistrantAllowListed() throws Exception {
    setEppInput("domain_update_registrant.xml");
    persistReferencedEntities();
    persistDomain();
    // Only changes registrant, with both nameserver and registrant allow list on the TLD.
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    runFlow();
    assertThat(loadByKey(reloadResourceByForeignKey().getRegistrant().get()).getContactId())
        .isEqualTo("sh8013");
  }

  @Test
  void testSuccess_changeContactsAndRegistrant() throws Exception {
    setEppInput("domain_update_contacts_and_registrant.xml");
    persistReferencedEntities();
    persistDomainWithRegistrant();

    reloadResourceByForeignKey()
        .getContacts()
        .forEach(
            contact ->
                assertThat(loadByKey(contact.getContactKey()).getContactId()).isEqualTo("mak21"));
    assertThat(loadByKey(reloadResourceByForeignKey().getRegistrant().get()).getContactId())
        .isEqualTo("mak21");

    runFlow();

    reloadResourceByForeignKey()
        .getContacts()
        .forEach(
            contact ->
                assertThat(loadByKey(contact.getContactKey()).getContactId()).isEqualTo("sh8013"));
    assertThat(loadByKey(reloadResourceByForeignKey().getRegistrant().get()).getContactId())
        .isEqualTo("sh8013");
  }

  @Test
  void testSuccess_nameserverAndRegistrantAllowListed() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.foo"))
            .build());
    doSuccessfulTest();
  }

  @Test
  void testSuccess_tldWithNameserverAllowList_removeNameserver() throws Exception {
    setEppInput("domain_update_remove_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .addNameserver(
                loadByForeignKey(Host.class, "ns2.example.foo", clock.nowUtc()).get().createVKey())
            .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.foo", "ns2.example.foo"))
            .build());
    assertThat(reloadResourceByForeignKey().getNameservers())
        .contains(
            loadByForeignKey(Host.class, "ns1.example.foo", clock.nowUtc()).get().createVKey());
    clock.advanceOneMilli();
    runFlow();
    assertThat(reloadResourceByForeignKey().getNameservers())
        .doesNotContain(
            loadByForeignKey(Host.class, "ns1.example.foo", clock.nowUtc())
                .map(ImmutableObject::createVKey)
                .get());
  }

  @Test
  void testFailure_tldWithNameserverAllowList_removeLastNameserver() throws Exception {
    persistReferencedEntities();
    persistDomain();
    setEppInput("domain_update_remove_nameserver.xml");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    EppException thrown =
        assertThrows(
            NameserversNotSpecifiedForTldWithNameserverAllowListException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_domainCreateNotRestricted_doNotApplyServerProhibitedStatusCodes()
      throws Exception {
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
    assertAboutDomains().that(reloadResourceByForeignKey()).hasExactlyStatusValues(CLIENT_HOLD);
  }

  @Test
  void testFailure_freePremium_wrongFee() throws Exception {
    setEppInput("domain_update_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // This test should throw an exception, because the fee extension is required when the fee is not
  // zero.
  @Test
  void testFailure_missingFeeOnNonFreeUpdate() throws Exception {
    setEppInput("domain_update_wildcard.xml", ImmutableMap.of("DOMAIN", "non-free-update.tld"));
    persistReferencedEntities();
    persistDomain();
    EppException thrown =
        assertThrows(FeesRequiredForNonFreeOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistReferencedEntities();
    persistDomain();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-update");
    assertTldsFieldLogged("tld");
  }

  @Test
  void testSuperuserExtension_turnsOffAutorenew() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_update_superuser_extension.xml", ImmutableMap.of("AUTORENEWS", "false"));
    DateTime expirationTime = clock.nowUtc().plusYears(3);
    persistReferencedEntities();
    persistResource(
        persistDomain().asBuilder().setRegistrationExpirationTime(expirationTime).build());
    clock.advanceOneMilli();
    runFlowAsSuperuser();
    assertAboutDomains().that(reloadResourceByForeignKey()).hasAutorenewEndTime(expirationTime);
  }

  @Test
  void testSuperuserExtension_turnsOnAutorenew() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_update_superuser_extension.xml", ImmutableMap.of("AUTORENEWS", "true"));
    DateTime expirationTime = clock.nowUtc().plusYears(3);
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setAutorenewEndTime(Optional.of(expirationTime))
            .setRegistrationExpirationTime(expirationTime)
            .build());
    clock.advanceOneMilli();
    runFlowAsSuperuser();
    assertAboutDomains().that(reloadResourceByForeignKey()).hasNoAutorenewEndTime();
  }

  @Test
  void testAddDnsPublishStatus_enqueueDnsTask() throws Exception {
    setEppInput(
        "domain_update_status_change.xml",
        ImmutableMap.of("STATUS_ADD", "clientHold", "STATUS_REM", "clientTransferProhibited"));
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setDomainName("example.tld")
            .setStatusValues(ImmutableSet.of(CLIENT_TRANSFER_PROHIBITED))
            .build());
    runFlowAsSuperuser();
    assertDomainDnsRequests("example.tld");
  }

  @Test
  void testRemoveEveryDnsPublishStatus_enqueueDnsTask() throws Exception {
    setEppInput(
        "domain_update_status_change.xml",
        ImmutableMap.of("STATUS_REM", "serverHold", "STATUS_ADD", "clientTransferProhibited"));
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setDomainName("example.tld")
            .setStatusValues(ImmutableSet.of(SERVER_HOLD))
            .build());
    runFlowAsSuperuser();
    assertDomainDnsRequests("example.tld");
  }

  @Test
  void testChangeSomeOrNoChangeDnsPublishStatus_doNotEnqueueDnsTask() throws Exception {
    setEppInput(
        "domain_update_status_change.xml",
        ImmutableMap.of("STATUS_ADD", "clientUpdateProhibited", "STATUS_REM", "pendingDelete"));
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setDomainName("example.tld")
            .setStatusValues(ImmutableSet.of(PENDING_DELETE, SERVER_HOLD))
            .build());
    runFlowAsSuperuser();
    assertNoDnsRequests();
  }
}
