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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Suppliers.memoize;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.toArray;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.config.RegistryConfig.getContactAndHostRoidSuffix;
import static google.registry.config.RegistryConfig.getContactAutomaticTransferLength;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.EppResourceUtils.createRepoId;
import static google.registry.model.IdService.allocateId;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.model.ResourceTransferUtils.createTransferResponse;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.tld.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.ofyTmOrDoNothing;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.difference;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DomainNameUtils.ACE_PREFIX_REGEX;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.util.Arrays.asList;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldState;
import google.registry.model.tld.Registry.TldType;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.tld.label.ReservedList;
import google.registry.model.tld.label.ReservedListDao;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.tmch.LordnTaskUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;

/** Static utils for setting up test resources. */
public class DatabaseHelper {

  // The following two fields are injected by ReplayExtension.

  // If this is true, all of the methods that save to the datastore do so with backup.
  private static boolean alwaysSaveWithBackup;

  // If the clock is defined, it will always be advanced by one millsecond after a transaction.
  private static FakeClock clock;

  private static final Supplier<String[]> DEFAULT_PREMIUM_LIST_CONTENTS =
      memoize(
          () ->
              toArray(
                  Splitter.on('\n')
                      .split(
                          readResourceUtf8(
                              DatabaseHelper.class, "default_premium_list_testdata.csv")),
                  String.class));

  public static void setAlwaysSaveWithBackup(boolean enable) {
    alwaysSaveWithBackup = enable;
  }

  public static void setClock(FakeClock fakeClock) {
    clock = fakeClock;
  }

  private static void maybeAdvanceClock() {
    if (clock != null) {
      clock.advanceOneMilli();
    }
  }

  public static HostResource newHostResource(String hostName) {
    return newHostResourceWithRoid(hostName, generateNewContactHostRoid());
  }

  public static HostResource newHostResourceWithRoid(String hostName, String repoId) {
    return new HostResource.Builder()
        .setHostName(hostName)
        .setCreationRegistrarId("TheRegistrar")
        .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setRepoId(repoId)
        .build();
  }

  public static DomainBase newDomainBase(String domainName) {
    String repoId = generateNewDomainRoid(getTldFromDomainName(domainName));
    return newDomainBase(domainName, repoId, persistActiveContact("contact1234"));
  }

  public static DomainBase newDomainBase(String domainName, ContactResource contact) {
    return newDomainBase(
        domainName, generateNewDomainRoid(getTldFromDomainName(domainName)), contact);
  }

  public static DomainBase newDomainBase(String domainName, HostResource... hosts) {
    ImmutableSet<VKey<HostResource>> hostKeys =
        Arrays.stream(hosts).map(HostResource::createVKey).collect(toImmutableSet());
    return newDomainBase(domainName).asBuilder().setNameservers(hostKeys).build();
  }

  public static DomainBase newDomainBase(
      String domainName, String repoId, ContactResource contact) {
    VKey<ContactResource> contactKey = contact.createVKey();
    return new DomainBase.Builder()
        .setRepoId(repoId)
        .setDomainName(domainName)
        .setCreationRegistrarId("TheRegistrar")
        .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setRegistrant(contactKey)
        .setContacts(
            ImmutableSet.of(
                DesignatedContact.create(Type.ADMIN, contactKey),
                DesignatedContact.create(Type.TECH, contactKey)))
        .setRegistrationExpirationTime(END_OF_TIME)
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
        .setCreationRegistrarId("TheRegistrar")
        .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
        .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setCreationTimeForTest(START_OF_TIME)
        .build();
  }

  public static Registry newRegistry(String tld, String roidSuffix) {
    return newRegistry(tld, roidSuffix, ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY));
  }

  public static Registry newRegistry(
      String tld, String roidSuffix, ImmutableSortedMap<DateTime, TldState> tldStates) {
    return setupRegistry(new Registry.Builder(), tld, roidSuffix, tldStates);
  }

  public static Registry newRegistry(
      String tld,
      String roidSuffix,
      ImmutableSortedMap<DateTime, TldState> tldStates,
      TldType tldType) {
    return setupRegistry(new Registry.Builder().setTldType(tldType), tld, roidSuffix, tldStates);
  }

  private static Registry setupRegistry(
      Registry.Builder registryBuilder,
      String tld,
      String roidSuffix,
      ImmutableSortedMap<DateTime, TldState> tldStates) {
    return registryBuilder
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
        .setPremiumList(persistPremiumList(tld, USD, DEFAULT_PREMIUM_LIST_CONTENTS.get()))
        .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
        .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
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
      String hostName, DomainBase superordinateDomain) {
    checkNotNull(superordinateDomain);
    return persistResource(
        newHostResource(hostName)
            .asBuilder()
            .setSuperordinateDomain(superordinateDomain.createVKey())
            .setInetAddresses(
                ImmutableSet.of(InetAddresses.forString("1080:0:0:0:8:800:200C:417A")))
            .build());
  }

  /** Persists a host resource with the given hostname deleted at the specified time. */
  public static HostResource persistDeletedHost(String hostName, DateTime deletionTime) {
    return persistResource(
        newHostResource(hostName).asBuilder().setDeletionTime(deletionTime).build());
  }

  public static DomainBase persistActiveDomain(String domainName) {
    return persistResource(newDomainBase(domainName));
  }

  public static DomainBase persistActiveDomain(String domainName, DateTime creationTime) {
    return persistResource(
        newDomainBase(domainName).asBuilder().setCreationTimeForTest(creationTime).build());
  }

  public static DomainBase persistActiveDomain(
      String domainName, DateTime creationTime, DateTime expirationTime) {
    return persistResource(
        newDomainBase(domainName)
            .asBuilder()
            .setCreationTimeForTest(creationTime)
            .setRegistrationExpirationTime(expirationTime)
            .build());
  }

  /** Persists a domain resource with the given domain name deleted at the specified time. */
  public static DomainBase persistDeletedDomain(String domainName, DateTime deletionTime) {
    return persistDomainAsDeleted(newDomainBase(domainName), deletionTime);
  }

  /**
   * Returns a persisted domain that is the passed-in domain modified to be deleted at the specified
   * time.
   */
  public static DomainBase persistDomainAsDeleted(DomainBase domain, DateTime deletionTime) {
    return persistResource(domain.asBuilder().setDeletionTime(deletionTime).build());
  }

  /** Persists a domain and enqueues a LORDN task of the appropriate type for it. */
  public static DomainBase persistDomainAndEnqueueLordn(final DomainBase domain) {
    final DomainBase persistedDomain = persistResource(domain);
    // Calls {@link LordnTaskUtils#enqueueDomainBaseTask} wrapped in a transaction so that the
    // transaction time is set correctly.
    tm().transactNew(() -> LordnTaskUtils.enqueueDomainBaseTask(persistedDomain));
    maybeAdvanceClock();
    return persistedDomain;
  }

  public static ReservedList persistReservedList(String listName, String... lines) {
    return persistReservedList(listName, true, lines);
  }

  public static ReservedList persistReservedList(ReservedList reservedList) {
    ReservedListDao.save(reservedList);
    maybeAdvanceClock();
    return reservedList;
  }

  public static ReservedList persistReservedList(
      String listName, boolean shouldPublish, String... lines) {
    ReservedList reservedList =
        new ReservedList.Builder()
            .setName(listName)
            .setReservedListMapFromLines(ImmutableList.copyOf(lines))
            .setShouldPublish(shouldPublish)
            .setCreationTimestamp(DateTime.now(DateTimeZone.UTC))
            .build();
    return persistReservedList(reservedList);
  }

  /**
   * Persists a premium list and its child entities directly without writing commit logs.
   *
   * <p>Avoiding commit logs is important because a simple default premium list is persisted for
   * each TLD that is created in tests, and clocks would need to be mocked using an auto-
   * incrementing FakeClock for all tests in order to persist the commit logs properly because of
   * the requirement to have monotonically increasing timestamps.
   */
  public static PremiumList persistPremiumList(
      String listName, CurrencyUnit currencyUnit, String... lines) {
    checkState(lines.length != 0, "Must provide at least one premium entry");
    PremiumList partialPremiumList = new PremiumList.Builder().setName(listName).build();
    ImmutableMap<String, PremiumEntry> entries = partialPremiumList.parse(asList(lines));
    PremiumList premiumList =
        partialPremiumList
            .asBuilder()
            .setCreationTimestamp(DateTime.now(DateTimeZone.UTC))
            .setCurrency(currencyUnit)
            .setLabelsToPrices(
                entries.entrySet().stream()
                    .collect(
                        toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().getValue())))
            .build();
    // Since we used to persist a PremiumList to Datastore here, it is necessary to allocate an ID
    // here to prevent breaking some of the hard-coded flow tests. IDs in tests are allocated in a
    // strictly increasing sequence, if we don't pad out the ID here, we would have to renumber
    // hundreds of unit tests.
    allocateId();
    PremiumListDao.save(premiumList);
    maybeAdvanceClock();
    return premiumList;
  }

  /** Creates and persists a tld. */
  public static void createTld(String tld) {
    createTld(tld, GENERAL_AVAILABILITY);
  }

  public static void createTld(String tld, String roidSuffix) {
    createTld(tld, roidSuffix, ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY));
  }

  /** Creates and persists the given TLDs. */
  public static void createTlds(String... tlds) {
    for (String tld : tlds) {
      createTld(tld, GENERAL_AVAILABILITY);
    }
  }

  public static void createTld(String tld, TldState tldState) {
    createTld(tld, ImmutableSortedMap.of(START_OF_TIME, tldState));
  }

  public static void createTld(String tld, ImmutableSortedMap<DateTime, TldState> tldStates) {
    // Coerce the TLD string into a valid ROID suffix.
    String roidSuffix =
        Ascii.toUpperCase(tld.replaceFirst(ACE_PREFIX_REGEX, "").replace('.', '_'))
            .replace('-', '_');
    createTld(tld, roidSuffix.length() > 8 ? roidSuffix.substring(0, 8) : roidSuffix, tldStates);
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

  /**
   * Deletes "domain" and all history records, billing events, poll messages and subordinate hosts.
   */
  public static void deleteTestDomain(DomainBase domain, DateTime now) {
    Iterable<BillingEvent> billingEvents = getBillingEvents(domain);
    Iterable<? extends HistoryEntry> historyEntries =
        HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey());
    Iterable<PollMessage> pollMessages = loadAllOf(PollMessage.class);
    tm().transact(
            () -> {
              deleteResource(domain);
              for (BillingEvent event : billingEvents) {
                deleteResource(event);
              }
              for (PollMessage pollMessage : pollMessages) {
                deleteResource(pollMessage);
              }
              domain
                  .getSubordinateHosts()
                  .forEach(
                      hostname ->
                          deleteResource(
                              EppResourceUtils.loadByForeignKey(HostResource.class, hostname, now)
                                  .get()));
              for (HistoryEntry hist : historyEntries) {
                deleteResource(hist);
              }
            });
  }

  public static void allowRegistrarAccess(String registrarId, String tld) {
    Registrar registrar = loadRegistrar(registrarId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(union(registrar.getAllowedTlds(), tld)).build());
  }

  public static void disallowRegistrarAccess(String registrarId, String tld) {
    Registrar registrar = loadRegistrar(registrarId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(difference(registrar.getAllowedTlds(), tld)).build());
  }

  private static DomainTransferData.Builder createDomainTransferDataBuilder(
      DateTime requestTime, DateTime expirationTime) {
    return new DomainTransferData.Builder()
        .setTransferStatus(TransferStatus.PENDING)
        .setGainingRegistrarId("NewRegistrar")
        .setTransferRequestTime(requestTime)
        .setLosingRegistrarId("TheRegistrar")
        .setPendingTransferExpirationTime(expirationTime);
  }

  private static ContactTransferData.Builder createContactTransferDataBuilder(
      DateTime requestTime, DateTime expirationTime) {
    return new ContactTransferData.Builder()
        .setTransferStatus(TransferStatus.PENDING)
        .setGainingRegistrarId("NewRegistrar")
        .setTransferRequestTime(requestTime)
        .setLosingRegistrarId("TheRegistrar")
        .setPendingTransferExpirationTime(expirationTime);
  }

  public static PollMessage.OneTime createPollMessageForImplicitTransfer(
      EppResource resource,
      HistoryEntry historyEntry,
      String registrarId,
      DateTime requestTime,
      DateTime expirationTime,
      @Nullable DateTime extendedRegistrationExpirationTime) {
    TransferData transferData =
        createDomainTransferDataBuilder(requestTime, expirationTime)
            .setTransferredRegistrationExpirationTime(extendedRegistrationExpirationTime)
            .build();
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(registrarId)
        .setEventTime(expirationTime)
        .setMsg("Transfer server approved.")
        .setResponseData(ImmutableList.of(createTransferResponse(resource, transferData)))
        .setParent(historyEntry)
        .build();
  }

  public static BillingEvent.OneTime createBillingEventForTransfer(
      DomainBase domain, DomainHistory historyEntry, DateTime costLookupTime, DateTime eventTime) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(domain.getDomainName())
        .setEventTime(eventTime)
        .setBillingTime(
            eventTime.plus(Registry.get(domain.getTld()).getTransferGracePeriodLength()))
        .setRegistrarId("NewRegistrar")
        .setPeriodYears(1)
        .setCost(getDomainRenewCost(domain.getDomainName(), costLookupTime, 1))
        .setParent(historyEntry)
        .build();
  }

  public static ContactResource persistContactWithPendingTransfer(
      ContactResource contact, DateTime requestTime, DateTime expirationTime, DateTime now) {
    HistoryEntry historyEntryContactTransfer =
        persistResource(
            new ContactHistory.Builder()
                .setType(HistoryEntry.Type.CONTACT_TRANSFER_REQUEST)
                .setContact(persistResource(contact))
                .setModificationTime(now)
                .setRegistrarId(contact.getCurrentSponsorRegistrarId())
                .build()
                .toChildHistoryEntity());
    return persistResource(
        contact
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .setTransferData(
                createContactTransferDataBuilder(requestTime, expirationTime)
                    .setPendingTransferExpirationTime(now.plus(getContactAutomaticTransferLength()))
                    .setServerApproveEntities(
                        ImmutableSet.of(
                            // Pretend it's 3 days since the request
                            persistResource(
                                    createPollMessageForImplicitTransfer(
                                        contact,
                                        historyEntryContactTransfer,
                                        "NewRegistrar",
                                        requestTime,
                                        expirationTime,
                                        null))
                                .createVKey(),
                            persistResource(
                                    createPollMessageForImplicitTransfer(
                                        contact,
                                        historyEntryContactTransfer,
                                        "TheRegistrar",
                                        requestTime,
                                        expirationTime,
                                        null))
                                .createVKey()))
                    .setTransferRequestTrid(
                        Trid.create("transferClient-trid", "transferServer-trid"))
                    .build())
            .build());
  }

  public static DomainBase persistDomainWithDependentResources(
      String label,
      String tld,
      ContactResource contact,
      DateTime now,
      DateTime creationTime,
      DateTime expirationTime) {
    String domainName = String.format("%s.%s", label, tld);
    String repoId = generateNewDomainRoid(tld);
    DomainBase domain =
        persistResource(
            new DomainBase.Builder()
                .setRepoId(repoId)
                .setDomainName(domainName)
                .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
                .setCreationRegistrarId("TheRegistrar")
                .setCreationTimeForTest(creationTime)
                .setRegistrationExpirationTime(expirationTime)
                .setRegistrant(contact.createVKey())
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.ADMIN, contact.createVKey()),
                        DesignatedContact.create(Type.TECH, contact.createVKey())))
                .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("fooBAR")))
                .addGracePeriod(
                    GracePeriod.create(
                        GracePeriodStatus.ADD, repoId, now.plusDays(10), "TheRegistrar", null))
                .build());
    DomainHistory historyEntryDomainCreate =
        persistResource(
            new DomainHistory.Builder()
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(now)
                .setDomain(domain)
                .setRegistrarId(domain.getCreationRegistrarId())
                .build());
    BillingEvent.Recurring autorenewEvent =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(domainName)
                .setRegistrarId("TheRegistrar")
                .setEventTime(expirationTime)
                .setRecurrenceEndTime(END_OF_TIME)
                .setParent(historyEntryDomainCreate)
                .build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setTargetId(domainName)
                .setRegistrarId("TheRegistrar")
                .setEventTime(expirationTime)
                .setAutorenewEndTime(END_OF_TIME)
                .setMsg("Domain was auto-renewed.")
                .setParent(historyEntryDomainCreate)
                .build());
    return persistResource(
        domain
            .asBuilder()
            .setAutorenewBillingEvent(autorenewEvent.createVKey())
            .setAutorenewPollMessage(autorenewPollMessage.createVKey())
            .build());
  }

  public static DomainBase persistDomainWithPendingTransfer(
      DomainBase domain,
      DateTime requestTime,
      DateTime expirationTime,
      DateTime extendedRegistrationExpirationTime) {
    DomainHistory historyEntryDomainTransfer =
        persistResource(
            new DomainHistory.Builder()
                .setType(HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST)
                .setModificationTime(tm().transact(() -> tm().getTransactionTime()))
                .setDomain(domain)
                .setRegistrarId("TheRegistrar")
                .build());
    BillingEvent.OneTime transferBillingEvent =
        persistResource(
            createBillingEventForTransfer(
                domain, historyEntryDomainTransfer, requestTime, expirationTime));
    BillingEvent.Recurring gainingClientAutorenewEvent =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setTargetId(domain.getDomainName())
                .setRegistrarId("NewRegistrar")
                .setEventTime(extendedRegistrationExpirationTime)
                .setRecurrenceEndTime(END_OF_TIME)
                .setParent(historyEntryDomainTransfer)
                .build());
    PollMessage.Autorenew gainingClientAutorenewPollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setTargetId(domain.getDomainName())
                .setRegistrarId("NewRegistrar")
                .setEventTime(extendedRegistrationExpirationTime)
                .setAutorenewEndTime(END_OF_TIME)
                .setMsg("Domain was auto-renewed.")
                .setParent(historyEntryDomainTransfer)
                .build());
    // Modify the existing autorenew event to reflect the pending transfer.
    persistResource(
        transactIfJpaTm(
            () ->
                tm().loadByKey(domain.getAutorenewBillingEvent())
                    .asBuilder()
                    .setRecurrenceEndTime(expirationTime)
                    .build()));
    // Update the end time of the existing autorenew poll message. We must delete it if it has no
    // events left in it.
    PollMessage.Autorenew autorenewPollMessage =
        transactIfJpaTm(() -> tm().loadByKey(domain.getAutorenewPollMessage()));
    if (autorenewPollMessage.getEventTime().isBefore(expirationTime)) {
      persistResource(autorenewPollMessage.asBuilder().setAutorenewEndTime(expirationTime).build());
    } else {
      deleteResource(autorenewPollMessage);
    }
    DomainTransferData.Builder transferDataBuilder =
        createDomainTransferDataBuilder(requestTime, expirationTime);
    return persistResource(
        domain
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .setTransferData(
                transferDataBuilder
                    .setPendingTransferExpirationTime(expirationTime)
                    .setTransferredRegistrationExpirationTime(extendedRegistrationExpirationTime)
                    .setServerApproveBillingEvent(transferBillingEvent.createVKey())
                    .setServerApproveAutorenewEvent(gainingClientAutorenewEvent.createVKey())
                    .setServerApproveAutorenewPollMessage(
                        gainingClientAutorenewPollMessage.createVKey())
                    .setServerApproveEntities(
                        ImmutableSet.of(
                            transferBillingEvent.createVKey(),
                            gainingClientAutorenewEvent.createVKey(),
                            gainingClientAutorenewPollMessage.createVKey(),
                            persistResource(
                                    createPollMessageForImplicitTransfer(
                                        domain,
                                        historyEntryDomainTransfer,
                                        "NewRegistrar",
                                        requestTime,
                                        expirationTime,
                                        extendedRegistrationExpirationTime))
                                .createVKey(),
                            persistResource(
                                    createPollMessageForImplicitTransfer(
                                        domain,
                                        historyEntryDomainTransfer,
                                        "TheRegistrar",
                                        requestTime,
                                        expirationTime,
                                        extendedRegistrationExpirationTime))
                                .createVKey()))
                    .setTransferRequestTrid(
                        Trid.create("transferClient-trid", "transferServer-trid"))
                    .build())
            .build());
  }

  /** Persists and returns a {@link Registrar} with the specified attributes. */
  public static Registrar persistNewRegistrar(
      String registrarId,
      String registrarName,
      Registrar.Type type,
      @Nullable Long ianaIdentifier) {
    return persistSimpleResource(
        new Registrar.Builder()
            .setRegistrarId(registrarId)
            .setRegistrarName(registrarName)
            .setType(type)
            .setIanaIdentifier(ianaIdentifier)
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 Fake St"))
                    .setCity("Fakington")
                    .setCountryCode("US")
                    .build())
            .build());
  }

  /** Persists and returns a {@link Registrar} with the specified registrarId. */
  public static Registrar persistNewRegistrar(String registrarId) {
    return persistNewRegistrar(registrarId, registrarId + " name", Registrar.Type.REAL, 100L);
  }

  /** Persists and returns a list of {@link Registrar}s with the specified registrarIds. */
  public static ImmutableList<Registrar> persistNewRegistrars(String... registrarIds) {
    ImmutableList.Builder<Registrar> newRegistrars = new ImmutableList.Builder<>();
    for (String registrarId : registrarIds) {
      newRegistrars.add(persistNewRegistrar(registrarId));
    }
    return newRegistrars.build();
  }

  public static Iterable<BillingEvent> getBillingEvents() {
    return transactIfJpaTm(
        () ->
            Iterables.concat(
                tm().loadAllOf(BillingEvent.OneTime.class),
                tm().loadAllOf(BillingEvent.Recurring.class),
                tm().loadAllOf(BillingEvent.Cancellation.class)));
  }

  private static Iterable<BillingEvent> getBillingEvents(EppResource resource) {
    return transactIfJpaTm(
        () ->
            Iterables.concat(
                tm().loadAllOfStream(BillingEvent.OneTime.class)
                    .filter(oneTime -> oneTime.getDomainRepoId().equals(resource.getRepoId()))
                    .collect(toImmutableList()),
                tm().loadAllOfStream(BillingEvent.Recurring.class)
                    .filter(recurring -> recurring.getDomainRepoId().equals(resource.getRepoId()))
                    .collect(toImmutableList()),
                tm().loadAllOfStream(BillingEvent.Cancellation.class)
                    .filter(
                        cancellation -> cancellation.getDomainRepoId().equals(resource.getRepoId()))
                    .collect(toImmutableList())));
  }

  /** Assert that the actual billing event matches the expected one, ignoring IDs. */
  public static void assertBillingEventsEqual(BillingEvent actual, BillingEvent expected) {
    assertAboutImmutableObjects().that(actual).isEqualExceptFields(expected, "id");
  }

  /** Assert that the actual billing events match the expected ones, ignoring IDs and order. */
  public static void assertBillingEventsEqual(
      Iterable<BillingEvent> actual, Iterable<BillingEvent> expected) {
    assertThat(actual)
        .comparingElementsUsing(immutableObjectCorrespondence("id"))
        .containsExactlyElementsIn(expected);
  }

  /** Assert that the expected billing events are exactly the ones found in the fake Datastore. */
  public static void assertBillingEvents(BillingEvent... expected) {
    assertBillingEventsEqual(getBillingEvents(), asList(expected));
  }

  /** Assert that the expected billing events set is exactly the one found in the fake Datastore. */
  public static void assertBillingEvents(Set<BillingEvent> expected) {
    assertBillingEventsEqual(getBillingEvents(), expected);
  }

  /**
   * Assert that the expected billing events are exactly the ones found for the given EppResource.
   */
  public static void assertBillingEventsForResource(
      EppResource resource, BillingEvent... expected) {
    assertBillingEventsEqual(getBillingEvents(resource), asList(expected));
  }

  /** Assert that there are no billing events. */
  public static void assertNoBillingEvents() {
    assertThat(getBillingEvents()).isEmpty();
  }

  /**
   * Strips the billing event ID (really, sets it to a constant value) to facilitate comparison.
   *
   * <p>Note: Prefer {@link #assertPollMessagesEqual} when that is suitable.
   */
  public static BillingEvent stripBillingEventId(BillingEvent billingEvent) {
    return billingEvent.asBuilder().setId(1L).build();
  }

  /** Assert that the actual poll message matches the expected one, ignoring IDs. */
  public static void assertPollMessagesEqual(PollMessage actual, PollMessage expected) {
    assertAboutImmutableObjects().that(actual).isEqualExceptFields(expected, "id");
  }

  /** Assert that the actual poll messages match the expected ones, ignoring IDs and order. */
  public static void assertPollMessagesEqual(
      Iterable<PollMessage> actual, Iterable<PollMessage> expected) {
    assertThat(actual)
        .comparingElementsUsing(immutableObjectCorrespondence("id"))
        .containsExactlyElementsIn(expected);
  }

  public static void assertPollMessages(String registrarId, PollMessage... expected) {
    assertPollMessagesEqual(getPollMessages(registrarId), asList(expected));
  }

  public static void assertPollMessages(PollMessage... expected) {
    assertPollMessagesEqual(getPollMessages(), asList(expected));
  }

  public static void assertPollMessagesForResource(DomainContent domain, PollMessage... expected) {
    assertPollMessagesEqual(getPollMessages(domain), asList(expected));
  }

  public static ImmutableList<PollMessage> getPollMessages() {
    return ImmutableList.copyOf(transactIfJpaTm(() -> tm().loadAllOf(PollMessage.class)));
  }

  public static ImmutableList<PollMessage> getPollMessages(String registrarId) {
    return transactIfJpaTm(
        () ->
            tm().loadAllOf(PollMessage.class).stream()
                .filter(pollMessage -> pollMessage.getRegistrarId().equals(registrarId))
                .collect(toImmutableList()));
  }

  public static ImmutableList<PollMessage> getPollMessages(DomainContent domain) {
    return transactIfJpaTm(
        () ->
            tm().loadAllOf(PollMessage.class).stream()
                .filter(
                    pollMessage ->
                        pollMessage.getParentKey().getParent().getName().equals(domain.getRepoId()))
                .collect(toImmutableList()));
  }

  public static ImmutableList<PollMessage> getPollMessages(
      String registrarId, DateTime beforeOrAt) {
    return transactIfJpaTm(
        () ->
            tm().loadAllOf(PollMessage.class).stream()
                .filter(pollMessage -> pollMessage.getRegistrarId().equals(registrarId))
                .filter(pollMessage -> isBeforeOrAt(pollMessage.getEventTime(), beforeOrAt))
                .collect(toImmutableList()));
  }

  /** Gets all PollMessages associated with the given EppResource. */
  public static ImmutableList<PollMessage> getPollMessages(
      EppResource resource, String registrarId, DateTime now) {
    return transactIfJpaTm(
        () ->
            tm().loadAllOf(PollMessage.class).stream()
                .filter(
                    pollMessage ->
                        pollMessage
                            .getParentKey()
                            .getParent()
                            .getName()
                            .equals(resource.getRepoId()))
                .filter(pollMessage -> pollMessage.getRegistrarId().equals(registrarId))
                .filter(
                    pollMessage ->
                        pollMessage.getEventTime().isEqual(now)
                            || pollMessage.getEventTime().isBefore(now))
                .collect(toImmutableList()));
  }

  public static PollMessage getOnlyPollMessage(String registrarId) {
    return Iterables.getOnlyElement(getPollMessages(registrarId));
  }

  public static PollMessage getOnlyPollMessage(String registrarId, DateTime now) {
    return Iterables.getOnlyElement(getPollMessages(registrarId, now));
  }

  public static PollMessage getOnlyPollMessage(
      String registrarId, DateTime now, Class<? extends PollMessage> subType) {
    return getPollMessages(registrarId, now).stream()
        .filter(subType::isInstance)
        .map(subType::cast)
        .collect(onlyElement());
  }

  public static PollMessage getOnlyPollMessage(
      DomainContent domain,
      String registrarId,
      DateTime now,
      Class<? extends PollMessage> subType) {
    return getPollMessages(domain, registrarId, now).stream()
        .filter(subType::isInstance)
        .map(subType::cast)
        .collect(onlyElement());
  }

  public static void assertAllocationTokens(AllocationToken... expectedTokens) {
    assertThat(transactIfJpaTm(() -> tm().loadAllOf(AllocationToken.class)))
        .comparingElementsUsing(immutableObjectCorrespondence("updateTimestamp", "creationTime"))
        .containsExactlyElementsIn(expectedTokens);
  }

  /** Returns a newly allocated, globally unique domain repoId of the format HEX-TLD. */
  public static String generateNewDomainRoid(String tld) {
    return createDomainRepoId(allocateId(), tld);
  }

  /** Returns a newly allocated, globally unique contact/host repoId of the format HEX_TLD-ROID. */
  public static String generateNewContactHostRoid() {
    return createRepoId(allocateId(), getContactAndHostRoidSuffix());
  }

  /**
   * Persists a test resource to Datastore and returns it.
   *
   * <p>Tests should always use this method (or the shortcut persist methods in this class) to
   * persist test data, to avoid potentially subtle bugs related to race conditions and a stale ofy
   * session cache. Specifically, this method calls .now() on the save to force the write to
   * actually get sent to Datastore (although it does not force it to be applied) and clears the
   * session cache. If necessary, this method also updates the relevant {@link EppResourceIndex},
   * {@link ForeignKeyIndex}.
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

  private static <R> void saveResource(R resource, boolean wantBackup) {
    if (tm().isOfy()) {
      Consumer<Object> saver =
          wantBackup || alwaysSaveWithBackup ? tm()::put : tm()::putWithoutBackup;
      saver.accept(resource);
      if (resource instanceof EppResource) {
        EppResource eppResource = (EppResource) resource;
        persistEppResourceExtras(
            eppResource, EppResourceIndex.create(Key.create(eppResource)), saver);
      }
    } else {
      tm().put(resource);
    }
  }

  private static <R extends EppResource> void persistEppResourceExtras(
      R resource, EppResourceIndex index, Consumer<Object> saver) {
    assertWithMessage("Cannot persist an EppResource with a missing repoId in tests")
        .that(resource.getRepoId())
        .isNotEmpty();
    saver.accept(index);
    if (resource instanceof ForeignKeyedEppResource) {
      saver.accept(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
    }
  }

  private static <R> R persistResource(final R resource, final boolean wantBackup) {
    assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
        .that(resource)
        .isNotInstanceOf(Buildable.Builder.class);
    tm().transact(() -> saveResource(resource, wantBackup));
    maybeAdvanceClock();
    // Force the session cache to be cleared so that when we read the resource back, we read from
    // Datastore and not from the session cache. This is needed to trigger Objectify's load process
    // (unmarshalling entity protos to POJOs, nulling out empty collections, calling @OnLoad
    // methods, etc.) which is bypassed for entities loaded from the session cache.
    tm().clearSessionCache();
    return transactIfJpaTm(() -> tm().loadByEntity(resource));
  }

  /** Persists an EPP resource with the {@link EppResourceIndex} always going into bucket one. */
  public static <R extends EppResource> R persistEppResourceInFirstBucket(final R resource) {
    final EppResourceIndex eppResourceIndex =
        EppResourceIndex.create(Key.create(EppResourceIndexBucket.class, 1), Key.create(resource));
    tm().transact(
            () -> {
              if (tm().isOfy()) {
                Consumer<Object> saver = tm()::put;
                saver.accept(resource);
                persistEppResourceExtras(resource, eppResourceIndex, saver);
              } else {
                tm().put(resource);
              }
            });
    maybeAdvanceClock();
    tm().clearSessionCache();
    return transactIfJpaTm(() -> tm().loadByEntity(resource));
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
      tm().transact(() -> chunk.forEach(resource -> saveResource(resource, wantBackup)));
      maybeAdvanceClock();
    }
    // Force the session to be cleared so that when we read it back, we read from Datastore
    // and not from the transaction's session cache.
    tm().clearSessionCache();
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
    checkState(!tm().inTransaction());
    tm().transact(
            () -> {
              tm().put(resource);
              tm().put(
                      HistoryEntry.createBuilderForResource(resource)
                          .setRegistrarId(resource.getCreationRegistrarId())
                          .setType(getHistoryEntryType(resource))
                          .setModificationTime(tm().getTransactionTime())
                          .build());
              ofyTmOrDoNothing(
                  () -> tm().put(ForeignKeyIndex.create(resource, resource.getDeletionTime())));
            });
    maybeAdvanceClock();
    tm().clearSessionCache();
    return transactIfJpaTm(() -> tm().loadByEntity(resource));
  }

  /** Returns all of the history entries that are parented off the given EppResource. */
  public static List<HistoryEntry> getHistoryEntries(EppResource resource) {
    return HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey());
  }

  /**
   * Returns all of the history entries that are parented off the given EppResource, casted to the
   * corresponding subclass.
   */
  public static <T extends HistoryEntry> List<T> getHistoryEntries(
      EppResource resource, Class<T> subclazz) {
    return HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey(), subclazz);
  }

  /**
   * Returns all of the history entries that are parented off the given EppResource with the given
   * type.
   */
  public static ImmutableList<HistoryEntry> getHistoryEntriesOfType(
      EppResource resource, final HistoryEntry.Type type) {
    return getHistoryEntries(resource).stream()
        .filter(entry -> entry.getType().equals(type))
        .collect(toImmutableList());
  }

  /**
   * Returns all of the history entries that are parented off the given EppResource with the given
   * type and casted to the corresponding subclass.
   */
  public static <T extends HistoryEntry> ImmutableList<T> getHistoryEntriesOfType(
      EppResource resource, final HistoryEntry.Type type, Class<T> subclazz) {
    return getHistoryEntries(resource, subclazz).stream()
        .filter(entry -> entry.getType().equals(type))
        .collect(toImmutableList());
  }

  /**
   * Returns the only history entry of the given type, and throws an AssertionError if there are
   * zero or more than one.
   */
  public static HistoryEntry getOnlyHistoryEntryOfType(
      EppResource resource, final HistoryEntry.Type type) {
    return Iterables.getOnlyElement(getHistoryEntriesOfType(resource, type));
  }

  /**
   * Returns the only history entry of the given type, casted to the corresponding subtype, and
   * throws an AssertionError if there are zero or more than one.
   */
  public static <T extends HistoryEntry> T getOnlyHistoryEntryOfType(
      EppResource resource, final HistoryEntry.Type type, Class<T> subclazz) {
    return Iterables.getOnlyElement(getHistoryEntriesOfType(resource, type, subclazz));
  }

  private static HistoryEntry.Type getHistoryEntryType(EppResource resource) {
    if (resource instanceof ContactResource) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.CONTACT_CREATE
          : HistoryEntry.Type.CONTACT_UPDATE;
    } else if (resource instanceof HostResource) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.HOST_CREATE
          : HistoryEntry.Type.HOST_UPDATE;
    } else if (resource instanceof DomainBase) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.DOMAIN_CREATE
          : HistoryEntry.Type.DOMAIN_UPDATE;
    } else {
      throw new AssertionError();
    }
  }

  public static PollMessage getOnlyPollMessageForHistoryEntry(HistoryEntry historyEntry) {
    return Iterables.getOnlyElement(
        transactIfJpaTm(
            () ->
                tm().loadAllOf(PollMessage.class).stream()
                    .filter(
                        pollMessage -> pollMessage.getParentKey().equals(Key.create(historyEntry)))
                    .collect(toImmutableList())));
  }

  public static <T extends EppResource> HistoryEntry createHistoryEntryForEppResource(
      T parentResource) {
    return persistResource(
        HistoryEntry.createBuilderForResource(parentResource)
            .setType(getHistoryEntryType(parentResource))
            .setModificationTime(DateTime.now(DateTimeZone.UTC))
            .setRegistrarId(parentResource.getPersistedCurrentSponsorRegistrarId())
            .build());
  }

  /** Persists a single Objectify resource, without adjusting foreign resources or keys. */
  public static <R> R persistSimpleResource(final R resource) {
    return persistSimpleResources(ImmutableList.of(resource)).get(0);
  }

  /**
   * Like persistResource but for multiple entities, with no helper for saving
   * ForeignKeyedEppResources.
   */
  public static <R> ImmutableList<R> persistSimpleResources(final Iterable<R> resources) {
    insertSimpleResources(resources);
    return transactIfJpaTm(() -> tm().loadByEntities(resources));
  }

  /**
   * Like {@link #persistSimpleResources(Iterable)} but without reloading/returning the saved
   * entities.
   */
  public static <R> void insertSimpleResources(final Iterable<R> resources) {
    tm().transact(
            () -> {
              if (alwaysSaveWithBackup) {
                tm().insertAll(ImmutableList.copyOf(resources));
              } else {
                tm().insertAllWithoutBackup(ImmutableList.copyOf(resources));
              }
            });
    maybeAdvanceClock();
    // Force the session to be cleared so that when we read it back, we read from Datastore
    // and not from the transaction's session cache.
    tm().clearSessionCache();
  }

  public static void deleteResource(final Object resource) {
    if (alwaysSaveWithBackup) {
      tm().transact(() -> tm().delete(resource));
      maybeAdvanceClock();
    } else {
      transactIfJpaTm(() -> tm().deleteWithoutBackup(resource));
    }
    // Force the session to be cleared so that when we read it back, we read from Datastore and
    // not from the transaction's session cache.
    tm().clearSessionCache();
  }

  /** Force the create and update timestamps to get written into the resource. */
  public static <R> R cloneAndSetAutoTimestamps(final R resource) {
    R result;
    if (tm().isOfy()) {
      result =
          tm().transact(
                  () -> auditedOfy().load().fromEntity(auditedOfy().save().toEntity(resource)));
    } else {
      // We have to separate the read and write operation into different transactions
      // otherwise JPA would just return the input entity instead of actually creating a
      // clone.
      tm().transact(() -> tm().put(resource));
      result = tm().transact(() -> tm().loadByEntity(resource));
    }
    maybeAdvanceClock();
    return result;
  }

  /** Returns the entire map of {@link PremiumEntry}s for the given {@link PremiumList}. */
  public static ImmutableMap<String, PremiumEntry> loadPremiumEntries(PremiumList premiumList) {
    return Streams.stream(PremiumListDao.loadAllPremiumEntries(premiumList.getName()))
        .collect(toImmutableMap(PremiumEntry::getDomainLabel, Function.identity()));
  }

  /** Loads and returns the registrar with the given client ID, or throws IAE if not present. */
  public static Registrar loadRegistrar(String registrarId) {
    return checkArgumentPresent(
        Registrar.loadByRegistrarId(registrarId),
        "Error in tests: Registrar %s does not exist",
        registrarId);
  }

  /**
   * Loads all entities from all classes stored in the database.
   *
   * <p>This is not performant (it requires initializing and detaching all Hibernate entities so
   * that they can be used outside of the transaction in which they're loaded) and it should only be
   * used in situations where we need to verify that, for instance, a dry run flow hasn't affected
   * the database at all.
   */
  public static List<Object> loadAllEntities() {
    if (tm().isOfy()) {
      return auditedOfy().load().list();
    } else {
      return jpaTm()
          .transact(
              () -> {
                ImmutableList<? extends Class<?>> entityClasses =
                    jpaTm().getEntityManager().getMetamodel().getEntities().stream()
                        .map(javax.persistence.metamodel.Type::getJavaType)
                        .collect(toImmutableList());
                ImmutableList.Builder<Object> result = new ImmutableList.Builder<>();
                for (Class<?> entityClass : entityClasses) {
                  for (Object object : jpaTm().loadAllOf(entityClass)) {
                    result.add(object);
                  }
                }
                return result.build();
              });
    }
  }

  /**
   * Loads (i.e. reloads) the specified entity from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   */
  public static <T> T loadByEntity(T entity) {
    return transactIfJpaTm(() -> tm().loadByEntity(entity));
  }

  /**
   * Loads the specified entity by its key from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   */
  public static <T> T loadByKey(VKey<T> key) {
    return transactIfJpaTm(() -> tm().loadByKey(key));
  }

  /**
   * Loads the specified entity by its key from the DB or empty if it doesn't exist.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   */
  public static <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return transactIfJpaTm(() -> tm().loadByKeyIfPresent(key));
  }

  /**
   * Loads the specified entities by their keys from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   */
  public static <T> ImmutableCollection<T> loadByKeys(Iterable<? extends VKey<? extends T>> keys) {
    return transactIfJpaTm(() -> tm().loadByKeys(keys).values());
  }

  /**
   * Loads all of the entities of the specified type from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   */
  public static <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return transactIfJpaTm(() -> tm().loadAllOf(clazz));
  }

  /**
   * Loads the set of entities by their keys from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   *
   * <p>Nonexistent keys / entities are absent from the resulting map, but no {@link
   * NoSuchElementException} will be thrown.
   */
  public static <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys) {
    return transactIfJpaTm(() -> tm().loadByKeysIfPresent(keys));
  }

  /**
   * Loads all given entities from the database if possible.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the callsite.
   *
   * <p>Nonexistent entities are absent from the resulting list, but no {@link
   * NoSuchElementException} will be thrown.
   */
  public static <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return transactIfJpaTm(() -> tm().loadByEntitiesIfPresent(entities));
  }

  /** Returns whether or not the given entity exists in the database. */
  public static boolean existsInDatabase(Object object) {
    return transactIfJpaTm(() -> tm().exists(object));
  }

  /** Inserts the given entity/entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void insertInDb(T... entities) {
    jpaTm().transact(() -> jpaTm().insertAll(entities));
  }

  /** Inserts the given entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void insertInDb(ImmutableCollection<T> entities) {
    jpaTm().transact(() -> jpaTm().insertAll(entities));
  }

  /**
   * In JPA mode, asserts that the given entity is detached from the current entity manager.
   *
   * <p>Returns the original entity object.
   */
  public static <T> T assertDetachedFromEntityManager(T entity) {
    assertThat(jpaTm().getEntityManager().contains(entity)).isFalse();
    return entity;
  }

  /**
   * Sets a DATASTORE_PRIMARY_READ_ONLY state on the {@link DatabaseMigrationStateSchedule}.
   *
   * <p>In order to allow for tests to manipulate the clock how they need, we start the transitions
   * one millisecond after the clock's current time (in case the clock's current value is
   * START_OF_TIME). We then advance the clock one second so that we're in the
   * DATASTORE_PRIMARY_READ_ONLY phase.
   *
   * <p>We must use the current time, otherwise the setting of the migration state will fail due to
   * an invalid transition.
   */
  public static void setMigrationScheduleToDatastorePrimaryReadOnly(FakeClock fakeClock) {
    DateTime now = fakeClock.nowUtc();
    jpaTm()
        .transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    ImmutableSortedMap.of(
                        START_OF_TIME,
                        MigrationState.DATASTORE_ONLY,
                        now.plusMillis(1),
                        MigrationState.DATASTORE_PRIMARY,
                        now.plusMillis(2),
                        MigrationState.DATASTORE_PRIMARY_READ_ONLY)));
    fakeClock.advanceBy(Duration.standardSeconds(1));
  }

  /**
   * Sets a SQL_PRIMARY state on the {@link DatabaseMigrationStateSchedule}.
   *
   * <p>In order to allow for tests to manipulate the clock how they need, we start the transitions
   * one millisecond after the clock's current time (in case the clock's current value is
   * START_OF_TIME). We then advance the clock one second so that we're in the SQL_PRIMARY phase.
   *
   * <p>We must use the current time, otherwise the setting of the migration state will fail due to
   * an invalid transition.
   */
  public static void setMigrationScheduleToSqlPrimary(FakeClock fakeClock) {
    DateTime now = fakeClock.nowUtc();
    jpaTm()
        .transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    ImmutableSortedMap.of(
                        START_OF_TIME,
                        MigrationState.DATASTORE_ONLY,
                        now.plusMillis(1),
                        MigrationState.DATASTORE_PRIMARY,
                        now.plusMillis(2),
                        MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                        now.plusMillis(3),
                        MigrationState.SQL_PRIMARY)));
    fakeClock.advanceBy(Duration.standardSeconds(1));
  }

  /** Removes the database migration schedule, in essence transitioning to DATASTORE_ONLY. */
  public static void removeDatabaseMigrationSchedule() {
    // use the raw calls because going SQL_PRIMARY -> DATASTORE_ONLY is not valid
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .putIgnoringReadOnly(
                        new DatabaseMigrationStateSchedule(
                            DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP)));
    DatabaseMigrationStateSchedule.CACHE.invalidateAll();
  }

  private DatabaseHelper() {}
}
