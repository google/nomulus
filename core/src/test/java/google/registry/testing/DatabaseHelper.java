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
import static google.registry.model.tld.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
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
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
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
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;

/** Static utils for setting up test resources. */
public final class DatabaseHelper {

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

  public static void setClock(FakeClock fakeClock) {
    clock = fakeClock;
  }

  private static void maybeAdvanceClock() {
    if (clock != null) {
      clock.advanceOneMilli();
    }
  }

  public static Host newHost(String hostName) {
    return newHostWithRoid(hostName, generateNewContactHostRoid());
  }

  public static Host newHostWithRoid(String hostName, String repoId) {
    return new Host.Builder()
        .setHostName(hostName)
        .setCreationRegistrarId("TheRegistrar")
        .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setRepoId(repoId)
        .build();
  }

  public static Domain newDomain(String domainName) {
    String repoId = generateNewDomainRoid(getTldFromDomainName(domainName));
    return newDomain(domainName, repoId, persistActiveContact("contact1234"));
  }

  public static Domain newDomain(String domainName, Contact contact) {
    return newDomain(domainName, generateNewDomainRoid(getTldFromDomainName(domainName)), contact);
  }

  public static Domain newDomain(String domainName, Host... hosts) {
    ImmutableSet<VKey<Host>> hostKeys =
        Arrays.stream(hosts).map(Host::createVKey).collect(toImmutableSet());
    return newDomain(domainName).asBuilder().setNameservers(hostKeys).build();
  }

  public static Domain newDomain(String domainName, String repoId, Contact contact) {
    VKey<Contact> contactKey = contact.createVKey();
    return new Domain.Builder()
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
   * Returns a newly created {@link Contact} for the given contactId (which is the foreign key) with
   * an auto-generated repoId.
   */
  public static Contact newContact(String contactId) {
    return newContactWithRoid(contactId, generateNewContactHostRoid());
  }

  public static Contact newContactWithRoid(String contactId, String repoId) {
    return new Contact.Builder()
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

  public static Contact persistActiveContact(String contactId) {
    return persistResource(newContact(contactId));
  }

  /** Persists a contact resource with the given contact id deleted at the specified time. */
  public static Contact persistDeletedContact(String contactId, DateTime deletionTime) {
    return persistResource(newContact(contactId).asBuilder().setDeletionTime(deletionTime).build());
  }

  public static Host persistActiveHost(String hostName) {
    return persistResource(newHost(hostName));
  }

  public static Host persistActiveSubordinateHost(String hostName, Domain superordinateDomain) {
    checkNotNull(superordinateDomain);
    return persistResource(
        newHost(hostName)
            .asBuilder()
            .setSuperordinateDomain(superordinateDomain.createVKey())
            .setInetAddresses(
                ImmutableSet.of(InetAddresses.forString("1080:0:0:0:8:800:200C:417A")))
            .build());
  }

  /** Persists a host resource with the given hostname deleted at the specified time. */
  public static Host persistDeletedHost(String hostName, DateTime deletionTime) {
    return persistResource(newHost(hostName).asBuilder().setDeletionTime(deletionTime).build());
  }

  public static Domain persistActiveDomain(String domainName) {
    return persistResource(newDomain(domainName));
  }

  public static Domain persistActiveDomain(String domainName, DateTime creationTime) {
    return persistResource(
        newDomain(domainName).asBuilder().setCreationTimeForTest(creationTime).build());
  }

  public static Domain persistActiveDomain(
      String domainName, DateTime creationTime, DateTime expirationTime) {
    return persistResource(
        newDomain(domainName)
            .asBuilder()
            .setCreationTimeForTest(creationTime)
            .setRegistrationExpirationTime(expirationTime)
            .build());
  }

  /** Persists a domain resource with the given domain name deleted at the specified time. */
  public static Domain persistDeletedDomain(String domainName, DateTime deletionTime) {
    return persistDomainAsDeleted(newDomain(domainName), deletionTime);
  }

  /**
   * Returns a persisted domain that is the passed-in domain modified to be deleted at the specified
   * time.
   */
  public static Domain persistDomainAsDeleted(Domain domain, DateTime deletionTime) {
    return persistResource(domain.asBuilder().setDeletionTime(deletionTime).build());
  }

  /** Persists a domain and enqueues a LORDN task of the appropriate type for it. */
  public static Domain persistDomainAndEnqueueLordn(final Domain domain) {
    final Domain persistedDomain = persistResource(domain);
    // Calls {@link LordnTaskUtils#enqueueDomainTask} wrapped in a transaction so that the
    // transaction time is set correctly.
    tm().transact(() -> LordnTaskUtils.enqueueDomainTask(persistedDomain));
    maybeAdvanceClock();
    return persistedDomain;
  }

  /** Persists a {@link Recurring} and {@link HistoryEntry} for a domain that already exists. */
  public static Domain persistBillingRecurrenceForDomain(
      Domain domain, RenewalPriceBehavior renewalPriceBehavior, @Nullable Money renewalPrice) {
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(domain.getCreationTime())
                .setDomain(domain)
                .build());
    Recurring recurring =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setDomainHistory(historyEntry)
                .setRenewalPrice(renewalPrice)
                .setRenewalPriceBehavior(renewalPriceBehavior)
                .setRegistrarId(domain.getCreationRegistrarId())
                .setEventTime(domain.getCreationTime())
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRecurrenceEndTime(END_OF_TIME)
                .setTargetId(domain.getDomainName())
                .build());
    return persistResource(
        domain.asBuilder().setAutorenewBillingEvent(recurring.createVKey()).build());
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
    // here to prevent breaking some hard-coded flow tests. IDs in tests are allocated in a
    // strictly increasing sequence, if we don't pad out the ID here, we would have to renumber
    // hundreds of unit tests.
    allocateId();
    PremiumListDao.save(premiumList);
    maybeAdvanceClock();
    return premiumList;
  }

  /** Creates and persists a tld. */
  public static Registry createTld(String tld) {
    return createTld(tld, GENERAL_AVAILABILITY);
  }

  public static Registry createTld(String tld, String roidSuffix) {
    return createTld(tld, roidSuffix, ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY));
  }

  /** Creates and persists the given TLDs. */
  public static void createTlds(String... tlds) {
    for (String tld : tlds) {
      createTld(tld, GENERAL_AVAILABILITY);
    }
  }

  public static Registry createTld(String tld, TldState tldState) {
    return createTld(tld, ImmutableSortedMap.of(START_OF_TIME, tldState));
  }

  public static Registry createTld(String tld, ImmutableSortedMap<DateTime, TldState> tldStates) {
    // Coerce the TLD string into a valid ROID suffix.
    String roidSuffix =
        Ascii.toUpperCase(tld.replaceFirst(ACE_PREFIX_REGEX, "").replace('.', '_'))
            .replace('-', '_');
    return createTld(
        tld, roidSuffix.length() > 8 ? roidSuffix.substring(0, 8) : roidSuffix, tldStates);
  }

  public static Registry createTld(
      String tld, String roidSuffix, ImmutableSortedMap<DateTime, TldState> tldStates) {
    Registry registry = persistResource(newRegistry(tld, roidSuffix, tldStates));
    allowRegistrarAccess("TheRegistrar", tld);
    allowRegistrarAccess("NewRegistrar", tld);
    return registry;
  }

  public static void deleteTld(String tld) {
    deleteResource(Registry.get(tld));
    disallowRegistrarAccess("TheRegistrar", tld);
    disallowRegistrarAccess("NewRegistrar", tld);
  }

  /**
   * Deletes "domain" and all history records, billing events, poll messages and subordinate hosts.
   */
  public static void deleteTestDomain(Domain domain, DateTime now) {
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
                              EppResourceUtils.loadByForeignKey(Host.class, hostname, now).get()));
              for (HistoryEntry hist : historyEntries) {
                deleteResource(hist);
              }
            });
  }

  public static void allowRegistrarAccess(String registrarId, String tld) {
    Registrar registrar = loadRegistrar(registrarId);
    if (!registrar.getAllowedTlds().contains(tld)) {
      persistResource(
          registrar.asBuilder().setAllowedTlds(union(registrar.getAllowedTlds(), tld)).build());
    }
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
        .setHistoryEntry(historyEntry)
        .build();
  }

  public static BillingEvent.OneTime createBillingEventForTransfer(
      Domain domain, DomainHistory historyEntry, DateTime costLookupTime, DateTime eventTime) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(domain.getDomainName())
        .setEventTime(eventTime)
        .setBillingTime(
            eventTime.plus(Registry.get(domain.getTld()).getTransferGracePeriodLength()))
        .setRegistrarId("NewRegistrar")
        .setPeriodYears(1)
        .setCost(getDomainRenewCost(domain.getDomainName(), costLookupTime, 1))
        .setDomainHistory(historyEntry)
        .build();
  }

  public static Contact persistContactWithPendingTransfer(
      Contact contact, DateTime requestTime, DateTime expirationTime, DateTime now) {
    ContactHistory historyEntryContactTransfer =
        persistResource(
            new ContactHistory.Builder()
                .setType(HistoryEntry.Type.CONTACT_TRANSFER_REQUEST)
                .setContact(persistResource(contact))
                .setModificationTime(now)
                .setRegistrarId(contact.getCurrentSponsorRegistrarId())
                .build());
    return persistResource(
        contact
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .setTransferData(
                createContactTransferDataBuilder(requestTime, expirationTime)
                    .setPendingTransferExpirationTime(now.plus(getContactAutomaticTransferLength()))
                    .setServerApproveEntities(
                        historyEntryContactTransfer.getRepoId(),
                        historyEntryContactTransfer.getRevisionId(),
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

  public static Domain persistDomainWithDependentResources(
      String label,
      String tld,
      Contact contact,
      DateTime now,
      DateTime creationTime,
      DateTime expirationTime) {
    String domainName = String.format("%s.%s", label, tld);
    String repoId = generateNewDomainRoid(tld);
    Domain domain =
        persistResource(
            new Domain.Builder()
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
                .setDomainHistory(historyEntryDomainCreate)
                .build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setTargetId(domainName)
                .setRegistrarId("TheRegistrar")
                .setEventTime(expirationTime)
                .setAutorenewEndTime(END_OF_TIME)
                .setMsg("Domain was auto-renewed.")
                .setHistoryEntry(historyEntryDomainCreate)
                .build());
    return persistResource(
        domain
            .asBuilder()
            .setAutorenewBillingEvent(autorenewEvent.createVKey())
            .setAutorenewPollMessage(autorenewPollMessage.createVKey())
            .build());
  }

  public static Domain persistDomainWithPendingTransfer(
      Domain domain,
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
                .setDomainHistory(historyEntryDomainTransfer)
                .build());
    PollMessage.Autorenew gainingClientAutorenewPollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setTargetId(domain.getDomainName())
                .setRegistrarId("NewRegistrar")
                .setEventTime(extendedRegistrationExpirationTime)
                .setAutorenewEndTime(END_OF_TIME)
                .setMsg("Domain was auto-renewed.")
                .setHistoryEntry(historyEntryDomainTransfer)
                .build());
    // Modify the existing autorenew event to reflect the pending transfer.
    persistResource(
        tm().transact(
                () ->
                    tm().loadByKey(domain.getAutorenewBillingEvent())
                        .asBuilder()
                        .setRecurrenceEndTime(expirationTime)
                        .build()));
    // Update the end time of the existing autorenew poll message. We must delete it if it has no
    // events left in it.
    PollMessage.Autorenew autorenewPollMessage =
        tm().transact(() -> tm().loadByKey(domain.getAutorenewPollMessage()));
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
                        historyEntryDomainTransfer.getRepoId(),
                        historyEntryDomainTransfer.getRevisionId(),
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
            .setState(State.ACTIVE)
            .setIanaIdentifier(ianaIdentifier)
            .setBillingAccountMap(ImmutableMap.of(USD, "abc123"))
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
    return tm().transact(
            () ->
                Iterables.concat(
                    tm().loadAllOf(BillingEvent.OneTime.class),
                    tm().loadAllOf(BillingEvent.Recurring.class),
                    tm().loadAllOf(BillingEvent.Cancellation.class)));
  }

  private static Iterable<BillingEvent> getBillingEvents(EppResource resource) {
    return tm().transact(
            () ->
                Iterables.concat(
                    tm().loadAllOfStream(BillingEvent.OneTime.class)
                        .filter(oneTime -> oneTime.getDomainRepoId().equals(resource.getRepoId()))
                        .collect(toImmutableList()),
                    tm().loadAllOfStream(BillingEvent.Recurring.class)
                        .filter(
                            recurring -> recurring.getDomainRepoId().equals(resource.getRepoId()))
                        .collect(toImmutableList()),
                    tm().loadAllOfStream(BillingEvent.Cancellation.class)
                        .filter(
                            cancellation ->
                                cancellation.getDomainRepoId().equals(resource.getRepoId()))
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

  public static void assertPollMessagesForResource(DomainBase domain, PollMessage... expected) {
    assertPollMessagesEqual(getPollMessages(domain), asList(expected));
  }

  public static ImmutableList<PollMessage> getPollMessages() {
    return ImmutableList.copyOf(tm().transact(() -> tm().loadAllOf(PollMessage.class)));
  }

  public static ImmutableList<PollMessage> getPollMessages(String registrarId) {
    return tm().transact(
            () ->
                tm().loadAllOf(PollMessage.class).stream()
                    .filter(pollMessage -> pollMessage.getRegistrarId().equals(registrarId))
                    .collect(toImmutableList()));
  }

  public static ImmutableList<PollMessage> getPollMessages(DomainBase domain) {
    return tm().transact(
            () ->
                tm().loadAllOf(PollMessage.class).stream()
                    .filter(pollMessage -> pollMessage.getDomainRepoId().equals(domain.getRepoId()))
                    .collect(toImmutableList()));
  }

  public static ImmutableList<PollMessage> getPollMessages(
      String registrarId, DateTime beforeOrAt) {
    return tm().transact(
            () ->
                tm().loadAllOf(PollMessage.class).stream()
                    .filter(pollMessage -> pollMessage.getRegistrarId().equals(registrarId))
                    .filter(pollMessage -> isBeforeOrAt(pollMessage.getEventTime(), beforeOrAt))
                    .collect(toImmutableList()));
  }

  /** Gets all PollMessages associated with the given EppResource. */
  public static ImmutableList<PollMessage> getPollMessages(
      EppResource resource, String registrarId, DateTime now) {
    return tm().transact(
            () ->
                tm().loadAllOf(PollMessage.class).stream()
                    .filter(
                        pollMessage -> pollMessage.getDomainRepoId().equals(resource.getRepoId()))
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
      DomainBase domain, String registrarId, DateTime now, Class<? extends PollMessage> subType) {
    return getPollMessages(domain, registrarId, now).stream()
        .filter(subType::isInstance)
        .map(subType::cast)
        .collect(onlyElement());
  }

  public static void assertAllocationTokens(AllocationToken... expectedTokens) {
    assertThat(tm().transact(() -> tm().loadAllOf(AllocationToken.class)))
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

  /** Persists an object in the DB for tests. */
  public static <R extends ImmutableObject> R persistResource(final R resource) {
    assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
        .that(resource)
        .isNotInstanceOf(Buildable.Builder.class);
    tm().transact(() -> tm().put(resource));
    maybeAdvanceClock();
    return tm().transact(() -> tm().loadByEntity(resource));
  }

  /** Persists the specified resources to the DB. */
  public static <R extends ImmutableObject> void persistResources(final Iterable<R> resources) {
    for (R resource : resources) {
      assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
          .that(resource)
          .isNotInstanceOf(Buildable.Builder.class);
    }
    tm().transact(() -> resources.forEach(e -> tm().put(e)));
    maybeAdvanceClock();
  }

  /**
   * Saves an {@link EppResource} with partial history and commit log entries.
   *
   * <p>This was coded for testing RDE since its queries depend on the associated entries.
   *
   *
   * @see #persistResource(ImmutableObject)
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
            });
    maybeAdvanceClock();
    return tm().transact(() -> tm().loadByEntity(resource));
  }

  /** Returns all the history entries that are parented off the given EppResource. */
  public static List<HistoryEntry> getHistoryEntries(EppResource resource) {
    return HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey());
  }

  /**
   * Returns all the history entries that are parented off the given EppResource, cast to the
   * corresponding subclass.
   */
  public static <T extends HistoryEntry> List<T> getHistoryEntries(
      EppResource resource, Class<T> subclazz) {
    return HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey(), subclazz);
  }

  /**
   * Returns all the history entries that are parented off the given EppResource with the given
   * type.
   */
  public static ImmutableList<HistoryEntry> getHistoryEntriesOfType(
      EppResource resource, final HistoryEntry.Type type) {
    return getHistoryEntries(resource).stream()
        .filter(entry -> entry.getType().equals(type))
        .collect(toImmutableList());
  }

  /**
   * Returns all the history entries that are parented off the given EppResource with the given type
   * and cast to the corresponding subclass.
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
   * Returns the only history entry of the given type, cast to the corresponding subtype, and throws
   * an AssertionError if there are zero or more than one.
   */
  public static <T extends HistoryEntry> T getOnlyHistoryEntryOfType(
      EppResource resource, final HistoryEntry.Type type, Class<T> subclazz) {
    return Iterables.getOnlyElement(getHistoryEntriesOfType(resource, type, subclazz));
  }

  private static HistoryEntry.Type getHistoryEntryType(EppResource resource) {
    if (resource instanceof Contact) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.CONTACT_CREATE
          : HistoryEntry.Type.CONTACT_UPDATE;
    } else if (resource instanceof Host) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.HOST_CREATE
          : HistoryEntry.Type.HOST_UPDATE;
    } else if (resource instanceof Domain) {
      return resource.getRepoId() != null
          ? HistoryEntry.Type.DOMAIN_CREATE
          : HistoryEntry.Type.DOMAIN_UPDATE;
    } else {
      throw new AssertionError();
    }
  }

  public static PollMessage getOnlyPollMessageForHistoryEntry(HistoryEntry historyEntry) {
    return Iterables.getOnlyElement(
        tm().transact(
                () ->
                    tm().loadAllOf(PollMessage.class).stream()
                        .filter(
                            pollMessage ->
                                pollMessage.getResourceId().equals(historyEntry.getRepoId())
                                    && pollMessage.getHistoryRevisionId()
                                        == historyEntry.getRevisionId()
                                    && pollMessage
                                        .getType()
                                        .getResourceClass()
                                        .equals(historyEntry.getResourceType()))
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
    return tm().transact(() -> tm().loadByEntities(resources));
  }

  /**
   * Like {@link #persistSimpleResources(Iterable)} but without reloading/returning the saved
   * entities.
   */
  public static <R> void insertSimpleResources(final Iterable<R> resources) {
    tm().transact(() -> tm().putAll(ImmutableList.copyOf(resources)));
    maybeAdvanceClock();
  }

  public static void deleteResource(final Object resource) {
    tm().transact(() -> tm().delete(resource));
  }

  /** Force the create and update timestamps to get written into the resource. */
  public static <R> R cloneAndSetAutoTimestamps(final R resource) {
    // We have to separate the read and write operation into different transactions otherwise JPA
    // would just return the input entity instead of actually creating a clone.
    tm().transact(() -> tm().put(resource));
    R result = tm().transact(() -> tm().loadByEntity(resource));
    maybeAdvanceClock();
    return result;
  }

  /** Returns the entire map of {@link PremiumEntry}s for the given {@link PremiumList}. */
  public static ImmutableMap<String, PremiumEntry> loadPremiumEntries(PremiumList premiumList) {
    return PremiumListDao.loadAllPremiumEntries(premiumList.getName()).stream()
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
   * that they can be used outside the transaction in which they're loaded) and it should only be
   * used in situations where we need to verify that, for instance, a dry run flow hasn't affected
   * the database at all.
   */
  public static List<Object> loadAllEntities() {
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

  /**
   * Loads (i.e. reloads) the specified entity from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   */
  public static <T> T loadByEntity(T entity) {
    return tm().transact(() -> tm().loadByEntity(entity));
  }

  /**
   * Loads the specified entity by its key from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   */
  public static <T> T loadByKey(VKey<T> key) {
    return tm().transact(() -> tm().loadByKey(key));
  }

  /**
   * Loads the specified entity by its key from the DB or empty if it doesn't exist.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   */
  public static <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return tm().transact(() -> tm().loadByKeyIfPresent(key));
  }

  /**
   * Loads the specified entities by their keys from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   */
  public static <T> ImmutableCollection<T> loadByKeys(Iterable<? extends VKey<? extends T>> keys) {
    return tm().transact(() -> tm().loadByKeys(keys).values());
  }

  /**
   * Loads all the entities of the specified type from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   */
  public static <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return tm().transact(() -> tm().loadAllOf(clazz));
  }

  /**
   * Loads the set of entities by their keys from the DB.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   *
   * <p>Nonexistent keys / entities are absent from the resulting map, but no {@link
   * NoSuchElementException} will be thrown.
   */
  public static <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys) {
    return tm().transact(() -> tm().loadByKeysIfPresent(keys));
  }

  /**
   * Loads all given entities from the database if possible.
   *
   * <p>If the transaction manager is Cloud SQL, then this creates an inner wrapping transaction for
   * convenience, so you don't need to wrap it in a transaction at the call site.
   *
   * <p>Nonexistent entities are absent from the resulting list, but no {@link
   * NoSuchElementException} will be thrown.
   */
  public static <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return tm().transact(() -> tm().loadByEntitiesIfPresent(entities));
  }

  /** Returns whether or not the given entity exists in Cloud SQL. */
  public static boolean existsInDb(ImmutableObject object) {
    return jpaTm().transact(() -> jpaTm().exists(object));
  }

  /** Inserts the given entity/entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void insertInDb(T... entities) {
    jpaTm().transact(() -> jpaTm().insertAll(entities));
  }

  /** Inserts the given entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void insertInDb(ImmutableCollection<T> entities) {
    jpaTm().transact(() -> jpaTm().insertAll(entities));
  }

  /** Puts the given entity/entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void putInDb(T... entities) {
    jpaTm().transact(() -> jpaTm().putAll(entities));
  }

  /** Puts the given entities into Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void putInDb(ImmutableCollection<T> entities) {
    jpaTm().transact(() -> jpaTm().putAll(entities));
  }

  /** Updates the given entities in Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void updateInDb(T... entities) {
    jpaTm().transact(() -> jpaTm().updateAll(entities));
  }

  /** Updates the given entities in Cloud SQL in a single transaction. */
  public static <T extends ImmutableObject> void updateInDb(ImmutableCollection<T> entities) {
    jpaTm().transact(() -> jpaTm().updateAll(entities));
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
   * Sets a DATASTORE_PRIMARY_NO_ASYNC state on the {@link DatabaseMigrationStateSchedule}.
   *
   * <p>In order to allow for tests to manipulate the clock how they need, we start the transitions
   * one millisecond after the clock's current time (in case the clock's current value is
   * START_OF_TIME). We then advance the clock one second so that we're in the
   * DATASTORE_PRIMARY_READ_ONLY phase.
   *
   * <p>We must use the current time, otherwise the setting of the migration state will fail due to
   * an invalid transition.
   */
  public static void setMigrationScheduleToDatastorePrimaryNoAsync(FakeClock fakeClock) {
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
                        MigrationState.DATASTORE_PRIMARY_NO_ASYNC)));
    fakeClock.advanceBy(Duration.standardSeconds(1));
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
                        MigrationState.DATASTORE_PRIMARY_NO_ASYNC,
                        now.plusMillis(3),
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
                        MigrationState.DATASTORE_PRIMARY_NO_ASYNC,
                        now.plusMillis(3),
                        MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                        now.plusMillis(4),
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
                    .put(
                        new DatabaseMigrationStateSchedule(
                            DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP)));
    DatabaseMigrationStateSchedule.CACHE.invalidateAll();
  }

  private DatabaseHelper() {}
}
