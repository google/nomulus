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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Suppliers.memoize;
import static com.google.common.collect.Iterables.toArray;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.flows.ResourceFlowUtils.createTransferResponse;
import static google.registry.model.EppResourceUtils.createContactHostRoid;
import static google.registry.model.EppResourceUtils.createDomainRoid;
import static google.registry.model.domain.launch.ApplicationStatus.VALIDATED;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.difference;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DomainNameUtils.ACE_PREFIX_REGEX;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.util.Arrays.asList;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.cmd.Saver;
import google.registry.config.RegistryEnvironment;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.index.DomainApplicationIndex;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.poll.PollMessage;
import google.registry.model.pricing.PricingCategory;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.CategorizedPremiumList;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.Builder;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferStatus;
import google.registry.tmch.LordnTask;
import org.joda.money.Money;
import org.joda.time.DateTime;

import java.util.List;

/** Static utils for setting up test resources. */
public class DatastoreHelper {

  private static final Supplier<String[]> DEFAULT_PREMIUM_LIST_CONTENTS =
      memoize(new Supplier<String[]>() {
        @Override
        public String[] get() {
          return toArray(
              Splitter.on('\n').split(
                  readResourceUtf8(DatastoreHelper.class, "default_premium_list_testdata.csv")),
              String.class);
        }});

  public static HostResource newHostResource(String hostName) {
    return new HostResource.Builder()
        .setFullyQualifiedHostName(hostName)
        .setCurrentSponsorClientId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setRepoId(generateNewContactHostRoid())
        .build();
  }

  public static DomainResource newDomainResource(String domainName) {
    String repoId = generateNewDomainRoid(getTldFromDomainName(domainName));
    return newDomainResource(domainName, repoId, persistActiveContact("contact1234"));
  }

  public static DomainResource newDomainResource(String domainName, ContactResource contact) {
    return newDomainResource(
        domainName, generateNewDomainRoid(getTldFromDomainName(domainName)), contact);
  }

  public static DomainResource newDomainResource(String domainName, HostResource host) {
    return newDomainResource(domainName)
        .asBuilder()
        .setNameservers(ImmutableSet.of(Key.create(host)))
        .build();
  }

  public static DomainResource newDomainResource(
      String domainName, String repoId, ContactResource contact) {
    Key<ContactResource> contactKey = Key.create(contact);
    return new DomainResource.Builder()
        .setRepoId(repoId)
        .setFullyQualifiedDomainName(domainName)
        .setCreationClientId("TheRegistrar")
        .setCurrentSponsorClientId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setRegistrant(contactKey)
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(Type.ADMIN, contactKey),
            DesignatedContact.create(Type.TECH, contactKey)))
        .setRegistrationExpirationTime(END_OF_TIME)
        .build();
  }

  public static DomainApplication newDomainApplication(String domainName) {
    // This ensures that the domain application gets the next available repoId before the created
    // contact does, which is usually the applicationId 1.
    return newDomainApplication(
        domainName,
        generateNewDomainRoid(getTldFromDomainName(domainName)),
        persistActiveContact("contact1234"),
        LaunchPhase.SUNRISE);
  }

  public static DomainApplication newDomainApplication(String domainName, ContactResource contact) {
    return newDomainApplication(domainName, contact, LaunchPhase.SUNRISE);
  }

  public static DomainApplication newDomainApplication(
      String domainName, ContactResource contact, LaunchPhase phase) {
    return newDomainApplication(
        domainName,
        generateNewDomainRoid(getTldFromDomainName(domainName)),
        contact,
        phase);
  }

  public static DomainApplication newDomainApplication(
      String domainName, String repoId, ContactResource contact, LaunchPhase phase) {
    Key<ContactResource> contactKey = Key.create(contact);
    return new DomainApplication.Builder()
        .setRepoId(repoId)
        .setFullyQualifiedDomainName(domainName)
        .setCurrentSponsorClientId("TheRegistrar")
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setRegistrant(contactKey)
        .setContacts(ImmutableSet.of(
            DesignatedContact.create(Type.ADMIN, contactKey),
            DesignatedContact.create(Type.TECH, contactKey)))
        .setPhase(phase)
        .setApplicationStatus(VALIDATED)
        .addStatusValue(StatusValue.PENDING_CREATE)
        .build();
  }

  public static DomainApplication newSunriseApplication(String domainName) {
    return newSunriseApplication(domainName, persistActiveContact("contact1234"));
  }

  public static DomainApplication newSunriseApplication(
      String domainName, ContactResource contact) {
    return newDomainApplication(domainName, contact, LaunchPhase.SUNRISE)
        .asBuilder()
        .setEncodedSignedMarks(ImmutableList.of(EncodedSignedMark.create("base64", "abcdef")))
        .build();
  }

  /**
   * Returns a newly created {@link ContactResource} for the given contactId (which is the foreign
   * key) with an auto-generated repoId.
   */
  public static ContactResource newContactResource(String contactId) {
    return newContactResourceWithRoid(contactId, generateNewContactHostRoid());
  }

  public static ContactResource newContactResourceWithRoid(String contactId, String repoId) {
    return new ContactResource.Builder()
        .setRepoId(repoId)
        .setContactId(contactId)
        .setCurrentSponsorClientId("TheRegistrar")
        .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setCreationTimeForTest(START_OF_TIME)
        .build();
  }

  public static Registry newRegistry(String tld, String roidSuffix) {
    return newRegistry(
        tld, roidSuffix, ImmutableSortedMap.of(START_OF_TIME, TldState.GENERAL_AVAILABILITY));
  }

  public static Registry newRegistry(
      String tld, String roidSuffix, ImmutableSortedMap<DateTime, TldState> tldStates) {
    return new Registry.Builder()
        .setTldStr(tld)
        .setRoidSuffix(roidSuffix)
        .setTldStateTransitions(tldStates)
        // Set billing costs to distinct small primes to avoid masking bugs in tests.
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 11)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(USD)))
        .setCreateBillingCost(Money.of(USD, 13))
        .setRestoreBillingCost(Money.of(USD, 17))
        .setServerStatusChangeBillingCost(Money.of(USD, 19))
        // Always set a default premium list. Tests that don't want it can delete it.
        .setPremiumList(persistPremiumList(tld, DEFAULT_PREMIUM_LIST_CONTENTS.get()))
        .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
        .setDnsWriter(VoidDnsWriter.NAME)
        .build();
  }

  public static ContactResource persistActiveContact(String contactId) {
    return persistResource(newContactResource(contactId));
  }

  /** Persists a contact resource with the given contact id deleted at the specified time. */
  public static ContactResource persistDeletedContact(String contactId, DateTime deletionTime) {
    return persistResource(
        newContactResource(contactId).asBuilder().setDeletionTime(deletionTime).build());
  }

  public static HostResource persistActiveHost(String hostName) {
    return persistResource(newHostResource(hostName));
  }

  public static HostResource persistActiveSubordinateHost(
      String hostName, DomainResource superordinateDomain) {
    checkNotNull(superordinateDomain);
    return persistResource(
        newHostResource(hostName)
            .asBuilder()
            .setSuperordinateDomain(Key.create(superordinateDomain))
            .build());
  }

  /** Persists a host resource with the given hostname deleted at the specified time. */
  public static HostResource persistDeletedHost(String hostName, DateTime deletionTime) {
    return persistResource(
        newHostResource(hostName).asBuilder().setDeletionTime(deletionTime).build());
  }

  public static DomainResource persistActiveDomain(String domainName) {
    return persistResource(newDomainResource(domainName));
  }

  public static DomainApplication persistActiveDomainApplication(String domainName) {
    return persistResource(newDomainApplication(domainName));
  }

  public static DomainApplication persistActiveDomainApplication(
      String domainName, ContactResource contact, LaunchPhase phase) {
    return persistResource(newDomainApplication(domainName, contact, phase));
  }

  /**
   * Persists a domain application resource with the given domain name deleted at the specified
   * time.
   */
  public static DomainApplication persistDeletedDomainApplication(
      String domainName, DateTime deletionTime) {
    return persistResource(
        newDomainApplication(domainName).asBuilder().setDeletionTime(deletionTime).build());
  }

  /** Persists a domain resource with the given domain name deleted at the specified time. */
  public static DomainResource persistDeletedDomain(String domainName, DateTime deletionTime) {
    return persistDomainAsDeleted(newDomainResource(domainName), deletionTime);
  }

  /**
   * Returns a persisted domain that is the passed-in domain modified to be deleted at the specified
   * time.
   */
  public static DomainResource persistDomainAsDeleted(
      DomainResource domain, DateTime deletionTime) {
    return persistResource(domain.asBuilder().setDeletionTime(deletionTime).build());
  }

  /** Persists a domain and enqueues a LORDN task of the appropriate type for it. */
  public static DomainResource persistDomainAndEnqueueLordn(final DomainResource domain) {
    final DomainResource persistedDomain = persistResource(domain);
    // Calls {@link LordnTask#enqueueDomainResourceTask} wrapped in an ofy transaction so that the
    // transaction time is set correctly.
    ofy().transactNew(new VoidWork() {
      @Override
      public void vrun() {
        LordnTask.enqueueDomainResourceTask(persistedDomain);
      }});
    return persistedDomain;
  }

  public static ReservedList persistReservedList(String listName, String... lines) {
    return persistReservedList(listName, true, lines);
  }

  public static ReservedList persistReservedList(
    String listName, boolean shouldPublish, String... lines) {
    return persistResource(
        new ReservedList.Builder()
            .setName(listName)
            .setReservedListMapFromLines(ImmutableList.copyOf(lines))
            .setShouldPublish(shouldPublish)
            .build());
  }

  public static PremiumList persistPremiumList(String listName, String... lines) {
    Optional<PremiumList> existing = PremiumList.get(listName);
    return persistPremiumList(
        (existing.isPresent() ? existing.get().asBuilder() : new PremiumList.Builder())
            .setName(listName)
            .setPremiumListMapFromLines(ImmutableList.copyOf(lines))
            .build());
  }

  private static PremiumList persistPremiumList(PremiumList premiumList) {
    // Persist the list and its child entities directly, rather than using its helper method, so
    // that we can avoid writing commit logs. This would cause issues since many tests replace the
    // clock in Ofy with a non-advancing FakeClock, and commit logs currently require
    // monotonically increasing timestamps.
    ofy().saveWithoutBackup().entity(premiumList).now();
    ofy().saveWithoutBackup().entities(premiumList.getPremiumListEntries().values()).now();
    return premiumList;
  }

  public static CategorizedPremiumList persistCategorizedPremiumList(
      CategorizedPremiumList categorizedPremiumList) {
    // Persist the list and its child entities directly, rather than using its helper method, so
    // that we can avoid writing commit logs. This would cause issues since many tests replace the
    // clock in Ofy with a non-advancing FakeClock, and commit logs currently require
    // monotonically increasing timestamps.
    ofy().saveWithoutBackup().entity(categorizedPremiumList).now();
    ofy()
        .saveWithoutBackup()
        .entities(categorizedPremiumList.getPremiumListEntries().values())
        .now();
    return categorizedPremiumList;
  }

  /**
   * Method returns a CategorizedPremiumList to allow for multiple to be built out for testing
   * purposes
   *
   * @param map An ImmutableSortedMap that represents a DateTime value and associated
   *     PricingCategory
   * @param tld A string value representing a top level domain
   * @param label A string value representing a key for the map
   * @return A CategorizedPremiumList object
   */
  public static CategorizedPremiumList persistCategorizedPremiumList(
      ImmutableSortedMap<DateTime, String> map, String tld, String label) {

    CategorizedPremiumList list = new CategorizedPremiumList.Builder()
               .setName(tld)
               .setPremiumListMap(
                   ImmutableMap.of(
                       label,
                       new CategorizedPremiumList.CategorizedListEntry.Builder()
                           .setLabel(label)
                           .setPricingCategoryTransitions(map)
                           .build()))
               .build().saveAndUpdateEntries();
    createTld(tld);
    persistResource(list);
    persistResource(Registry.get(tld).asBuilder().setPremiumList(list).build());

    return list;
  }

  /**
   * Method returns a PricingCategory object
   *
   * @param category A string value representing the category
   * @param money A sting value representing the money "USD 5.00"
   * @return A PricingCategory object
   */
  public static PricingCategory persistPricingCategory(String category, String money) {
    PricingCategory pc =
        new PricingCategory.Builder().setName(category).setPrice(Money.parse(money)).build();
    persistResource(pc);
    return pc;
  }

  /** Creates and persists a tld. */
  public static void createTld(String tld) {
    createTld(tld, TldState.GENERAL_AVAILABILITY);
  }

  public static void createTld(String tld, String roidSuffix) {
    createTld(tld, roidSuffix, ImmutableSortedMap.of(START_OF_TIME, TldState.GENERAL_AVAILABILITY));
  }

  /** Creates and persists the given TLDs. */
  public static void createTlds(String... tlds) {
    for (String tld : tlds) {
      createTld(tld, TldState.GENERAL_AVAILABILITY);
    }
  }

  public static void createTld(String tld, TldState tldState) {
    createTld(tld, ImmutableSortedMap.of(START_OF_TIME, tldState));
  }

  public static void createTld(String tld, ImmutableSortedMap<DateTime, TldState> tldStates) {
    createTld(tld, Ascii.toUpperCase(tld.replaceFirst(ACE_PREFIX_REGEX, "")), tldStates);
  }

  public static void createTld(
      String tld, String roidSuffix, ImmutableSortedMap<DateTime, TldState> tldStates) {
    persistResource(newRegistry(tld, roidSuffix, tldStates));
    allowRegistrarAccess("TheRegistrar", tld);
    allowRegistrarAccess("NewRegistrar", tld);
  }

  public static void deleteTld(String tld) {
    deleteResource(Registry.get(tld));
    disallowRegistrarAccess("TheRegistrar", tld);
    disallowRegistrarAccess("NewRegistrar", tld);
  }

  public static void allowRegistrarAccess(String clientId, String tld) {
    Registrar registrar = Registrar.loadByClientId(clientId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(union(registrar.getAllowedTlds(), tld)).build());
  }

  private static void disallowRegistrarAccess(String clientId, String tld) {
    Registrar registrar = Registrar.loadByClientId(clientId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(difference(registrar.getAllowedTlds(), tld)).build());
  }

  private static Builder createTransferDataBuilder(DateTime requestTime, DateTime expirationTime) {
    return new TransferData.Builder()
        .setTransferStatus(TransferStatus.PENDING)
        .setGainingClientId("NewRegistrar")
        .setTransferRequestTime(requestTime)
        .setLosingClientId("TheRegistrar")
        .setPendingTransferExpirationTime(expirationTime);
  }

  private static Builder createTransferDataBuilder(
      DateTime requestTime, DateTime expirationTime, Integer extendedRegistrationYears) {
    return createTransferDataBuilder(requestTime, expirationTime)
        .setExtendedRegistrationYears(extendedRegistrationYears);
  }

  public static PollMessage.OneTime createPollMessageForImplicitTransfer(
      EppResource contact,
      HistoryEntry historyEntry,
      String clientId,
      DateTime requestTime,
      DateTime expirationTime,
      DateTime now) {
    return new PollMessage.OneTime.Builder()
        .setClientId(clientId)
        .setEventTime(expirationTime)
        .setMsg("Transfer server approved.")
        .setResponseData(ImmutableList.of(
            createTransferResponse(contact, createTransferDataBuilder(requestTime, expirationTime)
                .build(),
            now)))
        .setParent(historyEntry)
        .build();
  }

  public static BillingEvent.OneTime createBillingEventForTransfer(
      DomainResource domain,
      HistoryEntry historyEntry,
      DateTime costLookupTime,
      DateTime eventTime,
      Integer extendedRegistrationYears) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setEventTime(eventTime)
        .setBillingTime(
            eventTime.plus(Registry.get(domain.getTld()).getTransferGracePeriodLength()))
        .setClientId("NewRegistrar")
        .setPeriodYears(extendedRegistrationYears)
        .setCost(
            getDomainRenewCost(
                domain.getFullyQualifiedDomainName(), costLookupTime, extendedRegistrationYears))
        .setParent(historyEntry)
        .build();
  }

  public static ContactResource persistContactWithPendingTransfer(
      ContactResource contact,
      DateTime requestTime,
      DateTime expirationTime,
      DateTime now) {
    HistoryEntry historyEntryContactTransfer = persistResource(
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.CONTACT_TRANSFER_REQUEST)
            .setParent(contact)
            .build());
    return persistResource(
        contact.asBuilder()
            .setCurrentSponsorClientId("TheRegistrar")
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .setTransferData(createTransferDataBuilder(requestTime, expirationTime)
                    .setPendingTransferExpirationTime(now.plus(
                        RegistryEnvironment.get().config().getContactAutomaticTransferLength()))
                .setServerApproveEntities(
                    ImmutableSet.<Key<? extends TransferServerApproveEntity>>of(
                    // Pretend it's 3 days since the request
                    Key.create(persistResource(
                        createPollMessageForImplicitTransfer(
                            contact,
                            historyEntryContactTransfer,
                            "NewRegistrar",
                            requestTime,
                            expirationTime,
                            now))),
                    Key.create(persistResource(
                        createPollMessageForImplicitTransfer(
                            contact,
                            historyEntryContactTransfer,
                            "TheRegistrar",
                            requestTime,
                            expirationTime,
                            now)))))
                .setTransferRequestTrid(Trid.create("transferClient-trid", "transferServer-trid"))
                .build())
            .build());
  }

  public static DomainResource persistDomainWithPendingTransfer(
      DomainResource domain,
      DateTime requestTime,
      DateTime expirationTime,
      DateTime extendedRegistrationExpirationTime,
      int extendedRegistrationYears,
      DateTime now) {
    HistoryEntry historyEntryDomainTransfer = persistResource(
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST)
            .setParent(domain)
            .build());
    BillingEvent.OneTime transferBillingEvent = persistResource(createBillingEventForTransfer(
            domain,
            historyEntryDomainTransfer,
            requestTime,
            expirationTime,
            extendedRegistrationYears));
    BillingEvent.Recurring gainingClientAutorenewEvent = persistResource(
        new BillingEvent.Recurring.Builder()
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setReason(Reason.RENEW)
            .setTargetId(domain.getFullyQualifiedDomainName())
            .setClientId("NewRegistrar")
            .setEventTime(extendedRegistrationExpirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntryDomainTransfer)
            .build());
    PollMessage.Autorenew gainingClientAutorenewPollMessage = persistResource(
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getFullyQualifiedDomainName())
            .setClientId("NewRegistrar")
            .setEventTime(extendedRegistrationExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainTransfer)
            .build());
    // Modify the existing autorenew event to reflect the pending transfer.
    persistResource(
        ofy().load().key(domain.getAutorenewBillingEvent()).now().asBuilder()
            .setRecurrenceEndTime(expirationTime)
            .build());
    // Update the end time of the existing autorenew poll message. We must delete it if it has no
    // events left in it.
    PollMessage.Autorenew autorenewPollMessage =
        ofy().load().key(domain.getAutorenewPollMessage()).now();
    if (autorenewPollMessage.getEventTime().isBefore(expirationTime)) {
      persistResource(
          autorenewPollMessage.asBuilder()
              .setAutorenewEndTime(expirationTime)
              .build());
    } else {
      deleteResource(autorenewPollMessage);
    }
    Builder transferDataBuilder = createTransferDataBuilder(
        requestTime, expirationTime, extendedRegistrationYears);
    return persistResource(domain.asBuilder()
        .setCurrentSponsorClientId("TheRegistrar")
        .addStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(transferDataBuilder
            .setPendingTransferExpirationTime(expirationTime)
            .setServerApproveBillingEvent(Key.create(transferBillingEvent))
            .setServerApproveAutorenewEvent(Key.create(gainingClientAutorenewEvent))
            .setServerApproveAutorenewPollMessage(Key.create(gainingClientAutorenewPollMessage))
            .setServerApproveEntities(ImmutableSet.<Key<? extends TransferServerApproveEntity>>of(
                Key.create(transferBillingEvent),
                Key.create(gainingClientAutorenewEvent),
                Key.create(gainingClientAutorenewPollMessage),
                Key.create(persistResource(
                    createPollMessageForImplicitTransfer(
                        domain,
                        historyEntryDomainTransfer,
                        "NewRegistrar",
                        requestTime,
                        expirationTime,
                        now))),
                Key.create(persistResource(
                    createPollMessageForImplicitTransfer(
                        domain,
                        historyEntryDomainTransfer,
                        "TheRegistrar",
                        requestTime,
                        expirationTime,
                        now)))))
            .setTransferRequestTrid(Trid.create("transferClient-trid", "transferServer-trid"))
            .setExtendedRegistrationYears(extendedRegistrationYears)
            .build())
        .build());
  }

  /** Creates a stripped-down {@link Registrar} with the specified clientId and ianaIdentifier */
  public static Registrar persistNewRegistrar(String clientId, long ianaIdentifier) {
    return persistSimpleResource(
        new Registrar.Builder()
          .setClientId(clientId)
          .setType(Registrar.Type.REAL)
          .setIanaIdentifier(ianaIdentifier)
          .build());
  }

  private static Iterable<BillingEvent> getBillingEvents() {
    return Iterables.<BillingEvent>concat(
        ofy().load().type(BillingEvent.OneTime.class),
        ofy().load().type(BillingEvent.Recurring.class),
        ofy().load().type(BillingEvent.Cancellation.class));
  }

  private static Iterable<BillingEvent> getBillingEvents(EppResource resource) {
    return Iterables.<BillingEvent>concat(
        ofy().load().type(BillingEvent.OneTime.class).ancestor(resource),
        ofy().load().type(BillingEvent.Recurring.class).ancestor(resource),
        ofy().load().type(BillingEvent.Cancellation.class).ancestor(resource));
  }


  /** Assert that the expected billing events are exactly the ones found in the fake datastore. */
  public static void assertBillingEvents(BillingEvent... expected) throws Exception {
    assertThat(FluentIterable.from(getBillingEvents()).transform(BILLING_EVENT_ID_STRIPPER))
        .containsExactlyElementsIn(
            FluentIterable.from(asList(expected)).transform(BILLING_EVENT_ID_STRIPPER));
  }

  /** Assert that the expected billing events set is exactly the one found in the fake datastore. */
  public static void assertBillingEvents(ImmutableSet<BillingEvent> expected) throws Exception {
    assertThat(FluentIterable.from(getBillingEvents()).transform(BILLING_EVENT_ID_STRIPPER))
        .containsExactlyElementsIn(
            FluentIterable.from(expected.asList()).transform(BILLING_EVENT_ID_STRIPPER));
  }

  /**
   * Assert that the expected billing events are exactly the ones found for the given EppResource.
   */
  public static void assertBillingEventsForResource(
      EppResource resource, BillingEvent... expected) throws Exception {
    assertThat(FluentIterable.from(getBillingEvents(resource)).transform(BILLING_EVENT_ID_STRIPPER))
        .containsExactlyElementsIn(
            FluentIterable.from(asList(expected)).transform(BILLING_EVENT_ID_STRIPPER));
  }


  /** Assert that there are no billing events. */
  public static void assertNoBillingEvents() {
    assertThat(getBillingEvents()).isEmpty();
  }

  /** Helper to effectively erase the billing event ID to facilitate comparison. */
  public static final Function<BillingEvent, BillingEvent> BILLING_EVENT_ID_STRIPPER =
      new Function<BillingEvent, BillingEvent>() {
        @Override
        public BillingEvent apply(BillingEvent billingEvent) {
          // Can't use id=0 because that causes the builder to generate a new id.
          return billingEvent.asBuilder().setId(1L).build();
        }};

  public static ImmutableList<PollMessage> getPollMessages() {
    return FluentIterable.from(ofy().load().type(PollMessage.class)).toList();
  }

  public static ImmutableList<PollMessage> getPollMessages(String clientId) {
    return FluentIterable
        .from(ofy().load().type(PollMessage.class).filter("clientId", clientId))
        .toList();
  }

  public static ImmutableList<PollMessage> getPollMessages(String clientId, DateTime now) {
    return FluentIterable
        .from(ofy()
            .load()
            .type(PollMessage.class)
            .filter("clientId", clientId)
            .filter("eventTime <=", now.toDate()))
        .toList();
  }

  /** Gets all PollMessages associated with the given EppResource. */
  public static ImmutableList<PollMessage> getPollMessages(
      EppResource resource, String clientId, DateTime now) {
    return FluentIterable
        .from(ofy()
            .load()
            .type(PollMessage.class)
            .ancestor(resource)
            .filter("clientId", clientId)
            .filter("eventTime <=", now.toDate()))
        .toList();
  }

  public static PollMessage getOnlyPollMessage(String clientId) {
    return Iterables.getOnlyElement(getPollMessages(clientId));
  }

  public static PollMessage getOnlyPollMessage(String clientId, DateTime now) {
    return Iterables.getOnlyElement(getPollMessages(clientId, now));
  }

  public static PollMessage getOnlyPollMessage(
      String clientId,
      DateTime now,
      Class<? extends PollMessage> subType) {
    return Iterables.getOnlyElement(Iterables.filter(getPollMessages(clientId, now), subType));
  }

  public static PollMessage getOnlyPollMessage(
      EppResource resource,
      String clientId,
      DateTime now,
      Class<? extends PollMessage> subType) {
    return Iterables.getOnlyElement(
        Iterables.filter(getPollMessages(resource, clientId, now), subType));
  }

  /** Returns a newly allocated, globally unique domain repoId of the format HEX-TLD. */
  public static String generateNewDomainRoid(String tld) {
    return createDomainRoid(ObjectifyService.allocateId(), tld);
  }

  /**
   * Returns a newly allocated, globally unique contact/host repoId of the format
   * HEX_TLD-ROID.
   */
  public static String generateNewContactHostRoid() {
    return createContactHostRoid(ObjectifyService.allocateId());
  }

  /**
   * Persists a test resource to Datastore and returns it.
   *
   * <p>Tests should always use this method (or the shortcut persist methods in this class) to
   * persist test data, to avoid potentially subtle bugs related to race conditions and a stale
   * ofy() session cache. Specifically, this method calls .now() on the save to force the write to
   * actually get sent to datastore (although it does not force it to be applied) and clears the
   * session cache. If necessary, this method also updates the relevant {@link EppResourceIndex},
   * {@link ForeignKeyIndex} and {@link DomainApplicationIndex}.
   *
   * <p><b>Note:</b> Your resource will not be enrolled in a commit log. If you want backups, use
   * {@link #persistResourceWithCommitLog(Object)}.
   */
  public static <R> R persistResource(final R resource) {
    return persistResource(resource, false);
  }

  /** Same as {@link #persistResource(Object)} with backups enabled. */
  public static <R> R persistResourceWithCommitLog(final R resource) {
    return persistResource(resource, true);
  }

  private static <R> void saveResource(final R resource, final boolean wantBackup) {
    Saver saver = wantBackup ? ofy().save() : ofy().saveWithoutBackup();
    saver.entity(resource);
    if (resource instanceof EppResource) {
      EppResource eppResource = (EppResource) resource;
      assertWithMessage("Cannot persist an EppResource with a missing repoId in tests")
          .that(eppResource.getRepoId()).isNotEmpty();
      Key<EppResource> eppResourceKey = Key.create(eppResource);
      saver.entity(EppResourceIndex.create(eppResourceKey));
      if (resource instanceof ForeignKeyedEppResource) {
        saver.entity(ForeignKeyIndex.create(eppResource, eppResource.getDeletionTime()));
      }
      if (resource instanceof DomainApplication) {
        saver.entity(
            DomainApplicationIndex.createUpdatedInstance((DomainApplication) resource));
      }
    }
  }

  private static <R> R persistResource(final R resource, final boolean wantBackup) {
    assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
        .that(resource)
        .isNotInstanceOf(Buildable.Builder.class);
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        saveResource(resource, wantBackup);
      }});
    // Force the session to be cleared so that when we read it back, we read from the datastore
    // and not from the transaction cache or memcache.
    ofy().clearSessionCache();
    return ofy().load().entity(resource).now();
  }

  public static <R> void persistResources(final Iterable<R> resources) {
    persistResources(resources, false);
  }

  private static <R> void persistResources(final Iterable<R> resources, final boolean wantBackup) {
    for (R resource : resources) {
      assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
          .that(resource)
          .isNotInstanceOf(Buildable.Builder.class);
    }
    // Persist domains ten at a time, to avoid exceeding the entity group limit.
    for (final List<R> chunk : Iterables.partition(resources, 10)) {
      ofy().transact(new VoidWork() {
        @Override
        public void vrun() {
          for (R resource : chunk) {
            saveResource(resource, wantBackup);
          }
        }});
    }
    // Force the session to be cleared so that when we read it back, we read from the datastore
    // and not from the transaction cache or memcache.
    ofy().clearSessionCache();
    for (R resource : resources) {
      ofy().load().entity(resource).now();
    }
  }

  /**
   * Saves an {@link EppResource} with partial history and commit log entries.
   *
   * <p>This was coded for testing RDE since its queries depend on the associated entries.
   *
   * <p><b>Warning:</b> If you call this multiple times in a single test, you need to inject Ofy's
   * clock field and forward it by a millisecond between each subsequent call.
   *
   * @see #persistResource(Object)
   */
  public static <R extends EppResource> R persistEppResource(final R resource) {
    checkState(!ofy().inTransaction());
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        ofy().save().<ImmutableObject>entities(
            resource,
            new HistoryEntry.Builder()
                .setParent(resource)
                .setType(getHistoryEntryType(resource))
                .setModificationTime(ofy().getTransactionTime())
                .build());
        ofy().save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
      }});
    ofy().clearSessionCache();
    return ofy().load().entity(resource).safe();
  }

  /** Returns all of the history entries that are parented off the given EppResource. */
  public static List<HistoryEntry> getHistoryEntries(EppResource resource) {
    return ofy().load()
        .type(HistoryEntry.class)
        .ancestor(resource)
        .order("modificationTime")
        .list();
  }

  /**
   * Returns all of the history entries that are parented off the given EppResource with the given
   * type.
   */
  public static List<HistoryEntry> getHistoryEntriesOfType(
      EppResource resource, final HistoryEntry.Type type) {
    return FluentIterable.from(getHistoryEntries(resource))
        .filter(new Predicate<HistoryEntry>() {
          @Override
          public boolean apply(HistoryEntry entry) {
            return entry.getType() == type;
          }})
        .toList();
  }

  /**
   * Returns the only history entry of the given type, and throws an AssertionError if there are
   * zero or more than one.
   */
  public static HistoryEntry getOnlyHistoryEntryOfType(
      EppResource resource, final HistoryEntry.Type type) {
    List<HistoryEntry> historyEntries =  getHistoryEntriesOfType(resource, type);
    assertThat(historyEntries).hasSize(1);
    return historyEntries.get(0);
  }

  private static HistoryEntry.Type getHistoryEntryType(EppResource resource) {
    if (resource instanceof ContactResource) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.CONTACT_CREATE : HistoryEntry.Type.CONTACT_UPDATE;
    } else if (resource instanceof HostResource) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.HOST_CREATE : HistoryEntry.Type.HOST_UPDATE;
    } else if (resource instanceof DomainResource) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.DOMAIN_CREATE : HistoryEntry.Type.DOMAIN_UPDATE;
    } else {
      throw new AssertionError();
    }
  }

  public static PollMessage getOnlyPollMessageForHistoryEntry(HistoryEntry historyEntry) {
    return Iterables.getOnlyElement(ofy().load()
        .type(PollMessage.class)
        .ancestor(historyEntry));
  }

  public static <T extends EppResource> HistoryEntry createHistoryEntryForEppResource(
      T parentResource) {
    return persistResource(new HistoryEntry.Builder().setParent(parentResource).build());
  }

  /** Persists a single Objectify resource, without adjusting foreign resources or keys. */
  public static <R> R persistSimpleResource(final R resource) {
    return persistSimpleResources(ImmutableList.of(resource)).get(0);
  }

  /**
   * Like persistResource but for multiple entities, with no helper for saving
   * ForeignKeyedEppResources.
   *
   * @see "http://docs.objectify-appengine.googlecode.com/git/apidocs/com/googlecode/objectify/cmd/Loader.htmls#entities(java.lang.Iterable)"
   */
  public static <R> ImmutableList<R> persistSimpleResources(final Iterable<R> resources) {
    ofy().transact(new VoidWork(){
      @Override
      public void vrun() {
        ofy().saveWithoutBackup().entities(resources);
      }});
    // Force the session to be cleared so that when we read it back, we read from the datastore
    // and not from the transaction cache or memcache.
    ofy().clearSessionCache();
    return ImmutableList.copyOf(ofy().load().entities(resources).values());
  }

  public static void deleteResource(final Object resource) {
    ofy().deleteWithoutBackup().entity(resource).now();
    // Force the session to be cleared so that when we read it back, we read from the datastore and
    // not from the transaction cache or memcache.
    ofy().clearSessionCache();
  }

  /** Force the create and update timestamps to get written into the resource. **/
  public static <R> R cloneAndSetAutoTimestamps(final R resource) {
    return ofy().transact(new Work<R>() {
      @Override
      public R run() {
        return ofy().load().fromEntity(ofy().save().toEntity(resource));
      }});
  }

  private DatastoreHelper() {}
}
