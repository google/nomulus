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
import static com.google.common.collect.Iterables.toArray;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.config.RegistryConfig.getContactAndHostRoidSuffix;
import static google.registry.config.RegistryConfig.getContactAutomaticTransferLength;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.EppResourceUtils.createRepoId;
import static google.registry.model.ResourceTransferUtils.createTransferResponse;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.label.PremiumListUtils.parentPremiumListEntriesOnRevision;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.difference;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DomainNameUtils.ACE_PREFIX_REGEX;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.util.Arrays.asList;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Saver;
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
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.poll.PollMessage;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.Registry.TldType;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumList.PremiumListRevision;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.tmch.LordnTaskUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Static utils for setting up test resources. */
public class DatastoreHelper {

  private static final Supplier<String[]> DEFAULT_PREMIUM_LIST_CONTENTS =
      memoize(
          () ->
              toArray(
                  Splitter.on('\n')
                      .split(
                          readResourceUtf8(
                              DatastoreHelper.class, "default_premium_list_testdata.csv")),
                  String.class));

  public static HostResource newHostResource(String hostName) {
    return new HostResource.Builder()
        .setFullyQualifiedHostName(hostName)
        .setCreationClientId("TheRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setRepoId(generateNewContactHostRoid())
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

  public static DomainBase newDomainBase(String domainName, HostResource host) {
    return newDomainBase(domainName)
        .asBuilder()
        .setNameservers(ImmutableSet.of(Key.create(host)))
        .build();
  }

  public static DomainBase newDomainBase(
      String domainName, String repoId, ContactResource contact) {
    Key<ContactResource> contactKey = Key.create(contact);
    return new DomainBase.Builder()
        .setRepoId(repoId)
        .setFullyQualifiedDomainName(domainName)
        .setCreationClientId("TheRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setCreationTimeForTest(START_OF_TIME)
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
        .setRegistrant(contactKey)
        .setContacts(ImmutableSet.of(
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
        .setCreationClientId("TheRegistrar")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
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
        .setPremiumList(persistPremiumList(tld, DEFAULT_PREMIUM_LIST_CONTENTS.get()))
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
            .setSuperordinateDomain(Key.create(superordinateDomain))
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
  public static DomainBase persistDomainAsDeleted(
      DomainBase domain, DateTime deletionTime) {
    return persistResource(domain.asBuilder().setDeletionTime(deletionTime).build());
  }

  /** Persists a domain and enqueues a LORDN task of the appropriate type for it. */
  public static DomainBase persistDomainAndEnqueueLordn(final DomainBase domain) {
    final DomainBase persistedDomain = persistResource(domain);
    // Calls {@link LordnTaskUtils#enqueueDomainBaseTask} wrapped in an ofy transaction so that
    // the
    // transaction time is set correctly.
    tm().transactNew(() -> LordnTaskUtils.enqueueDomainBaseTask(persistedDomain));
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

  /**
   * Persists a premium list and its child entities directly without writing commit logs.
   *
   * <p>Avoiding commit logs is important because a simple default premium list is persisted for
   * each TLD that is created in tests, and clocks would need to be mocked using an auto-
   * incrementing FakeClock for all tests in order to persist the commit logs properly because of
   * the requirement to have monotonically increasing timestamps.
   */
  public static PremiumList persistPremiumList(String listName, String... lines) {
    PremiumList premiumList = new PremiumList.Builder().setName(listName).build();
    ImmutableMap<String, PremiumListEntry> entries = premiumList.parse(asList(lines));
    PremiumListRevision revision = PremiumListRevision.create(premiumList, entries.keySet());
    ofy()
        .saveWithoutBackup()
        .entities(premiumList.asBuilder().setRevision(Key.create(revision)).build(), revision)
        .now();
    ofy()
        .saveWithoutBackup()
        .entities(parentPremiumListEntriesOnRevision(entries.values(), Key.create(revision)))
        .now();
    return ofy().load().entity(premiumList).now();
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

  public static void allowRegistrarAccess(String clientId, String tld) {
    Registrar registrar = loadRegistrar(clientId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(union(registrar.getAllowedTlds(), tld)).build());
  }

  public static void disallowRegistrarAccess(String clientId, String tld) {
    Registrar registrar = loadRegistrar(clientId);
    persistResource(
        registrar.asBuilder().setAllowedTlds(difference(registrar.getAllowedTlds(), tld)).build());
  }

  private static TransferData.Builder createTransferDataBuilder(
      DateTime requestTime, DateTime expirationTime) {
    return new TransferData.Builder()
        .setTransferStatus(TransferStatus.PENDING)
        .setGainingClientId("NewRegistrar")
        .setTransferRequestTime(requestTime)
        .setLosingClientId("TheRegistrar")
        .setPendingTransferExpirationTime(expirationTime);
  }

  public static PollMessage.OneTime createPollMessageForImplicitTransfer(
      EppResource resource,
      HistoryEntry historyEntry,
      String clientId,
      DateTime requestTime,
      DateTime expirationTime,
      @Nullable DateTime extendedRegistrationExpirationTime) {
    TransferData transferData =
        createTransferDataBuilder(requestTime, expirationTime)
            .setTransferredRegistrationExpirationTime(extendedRegistrationExpirationTime)
            .build();
    return new PollMessage.OneTime.Builder()
        .setClientId(clientId)
        .setEventTime(expirationTime)
        .setMsg("Transfer server approved.")
        .setResponseData(ImmutableList.of(createTransferResponse(resource, transferData)))
        .setParent(historyEntry)
        .build();
  }

  public static BillingEvent.OneTime createBillingEventForTransfer(
      DomainBase domain,
      HistoryEntry historyEntry,
      DateTime costLookupTime,
      DateTime eventTime) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setEventTime(eventTime)
        .setBillingTime(
            eventTime.plus(Registry.get(domain.getTld()).getTransferGracePeriodLength()))
        .setClientId("NewRegistrar")
        .setPeriodYears(1)
        .setCost(getDomainRenewCost(domain.getFullyQualifiedDomainName(), costLookupTime, 1))
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
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .addStatusValue(StatusValue.PENDING_TRANSFER)
            .setTransferData(createTransferDataBuilder(requestTime, expirationTime)
                    .setPendingTransferExpirationTime(now.plus(getContactAutomaticTransferLength()))
                .setServerApproveEntities(
                    ImmutableSet.of(
                    // Pretend it's 3 days since the request
                    Key.create(persistResource(
                        createPollMessageForImplicitTransfer(
                            contact,
                            historyEntryContactTransfer,
                            "NewRegistrar",
                            requestTime,
                            expirationTime,
                            null))),
                    Key.create(persistResource(
                        createPollMessageForImplicitTransfer(
                            contact,
                            historyEntryContactTransfer,
                            "TheRegistrar",
                            requestTime,
                            expirationTime,
                            null)))))
                .setTransferRequestTrid(Trid.create("transferClient-trid", "transferServer-trid"))
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
    DomainBase domain =
        new DomainBase.Builder()
            .setRepoId(generateNewDomainRoid(tld))
            .setFullyQualifiedDomainName(domainName)
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setCreationClientId("TheRegistrar")
            .setCreationTimeForTest(creationTime)
            .setRegistrationExpirationTime(expirationTime)
            .setRegistrant(Key.create(contact))
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, Key.create(contact)),
                    DesignatedContact.create(Type.TECH, Key.create(contact))))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("fooBAR")))
            .addGracePeriod(
                GracePeriod.create(GracePeriodStatus.ADD, now.plusDays(10), "foo", null))
            .build();
    HistoryEntry historyEntryDomainCreate =
        persistResource(
            new HistoryEntry.Builder()
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setParent(domain)
                .build());
    BillingEvent.Recurring autorenewEvent =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(domainName)
                .setClientId("TheRegistrar")
                .setEventTime(expirationTime)
                .setRecurrenceEndTime(END_OF_TIME)
                .setParent(historyEntryDomainCreate)
                .build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setTargetId(domainName)
                .setClientId("TheRegistrar")
                .setEventTime(expirationTime)
                .setAutorenewEndTime(END_OF_TIME)
                .setMsg("Domain was auto-renewed.")
                .setParent(historyEntryDomainCreate)
                .build());
    return persistResource(
        domain
            .asBuilder()
            .setAutorenewBillingEvent(Key.create(autorenewEvent))
            .setAutorenewPollMessage(Key.create(autorenewPollMessage))
            .build());
  }

  public static DomainBase persistDomainWithPendingTransfer(
      DomainBase domain,
      DateTime requestTime,
      DateTime expirationTime,
      DateTime extendedRegistrationExpirationTime) {
    HistoryEntry historyEntryDomainTransfer = persistResource(
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST)
            .setParent(domain)
            .build());
    BillingEvent.OneTime transferBillingEvent = persistResource(createBillingEventForTransfer(
            domain,
            historyEntryDomainTransfer,
            requestTime,
            expirationTime));
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
    TransferData.Builder transferDataBuilder =
        createTransferDataBuilder(requestTime, expirationTime);
    return persistResource(domain.asBuilder()
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .addStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(transferDataBuilder
            .setPendingTransferExpirationTime(expirationTime)
            .setTransferredRegistrationExpirationTime(extendedRegistrationExpirationTime)
            .setServerApproveBillingEvent(Key.create(transferBillingEvent))
            .setServerApproveAutorenewEvent(Key.create(gainingClientAutorenewEvent))
            .setServerApproveAutorenewPollMessage(Key.create(gainingClientAutorenewPollMessage))
            .setServerApproveEntities(ImmutableSet.of(
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
                        extendedRegistrationExpirationTime))),
                Key.create(persistResource(
                    createPollMessageForImplicitTransfer(
                        domain,
                        historyEntryDomainTransfer,
                        "TheRegistrar",
                        requestTime,
                        expirationTime,
                        extendedRegistrationExpirationTime)))))
            .setTransferRequestTrid(Trid.create("transferClient-trid", "transferServer-trid"))
            .build())
        .build());
  }

  /** Persists and returns a {@link Registrar} with the specified attributes. */
  public static Registrar persistNewRegistrar(
      String clientId, String registrarName, Registrar.Type type, @Nullable Long ianaIdentifier) {
    return persistSimpleResource(
        new Registrar.Builder()
            .setClientId(clientId)
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

  private static Iterable<BillingEvent> getBillingEvents() {
    return Iterables.concat(
        ofy().load().type(BillingEvent.OneTime.class),
        ofy().load().type(BillingEvent.Recurring.class),
        ofy().load().type(BillingEvent.Cancellation.class));
  }

  private static Iterable<BillingEvent> getBillingEvents(EppResource resource) {
    return Iterables.concat(
        ofy().load().type(BillingEvent.OneTime.class).ancestor(resource),
        ofy().load().type(BillingEvent.Recurring.class).ancestor(resource),
        ofy().load().type(BillingEvent.Cancellation.class).ancestor(resource));
  }

  /** Assert that the actual billing event matches the expected one, ignoring IDs. */
  public static void assertBillingEventsEqual(BillingEvent actual, BillingEvent expected) {
    assertThat(stripBillingEventId(actual)).isEqualTo(stripBillingEventId(expected));
  }

  /** Assert that the actual billing events match the expected ones, ignoring IDs and order. */
  public static void assertBillingEventsEqual(
      Iterable<BillingEvent> actual, Iterable<BillingEvent> expected) {
    assertThat(
            Streams.stream(actual)
                .map(DatastoreHelper::stripBillingEventId)
                .collect(toImmutableList()))
        .containsExactlyElementsIn(
            Streams.stream(expected)
                .map(DatastoreHelper::stripBillingEventId)
                .collect(toImmutableList()));
  }

  /** Assert that the expected billing events are exactly the ones found in the fake Datastore. */
  public static void assertBillingEvents(BillingEvent... expected) {
    assertBillingEventsEqual(getBillingEvents(), Arrays.asList(expected));
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
    assertThat(
            Streams.stream(getBillingEvents(resource))
                .map(DatastoreHelper::stripBillingEventId)
                .collect(toImmutableList()))
        .containsExactlyElementsIn(
            Arrays.stream(expected)
                .map(DatastoreHelper::stripBillingEventId)
                .collect(toImmutableList()));
  }

  /** Assert that there are no billing events. */
  public static void assertNoBillingEvents() {
    assertThat(getBillingEvents()).isEmpty();
  }

  /** Strips the billing event ID (really, sets it to a constant value) to facilitate comparison. */
  public static BillingEvent stripBillingEventId(BillingEvent billingEvent) {
    return billingEvent.asBuilder().setId(1L).build();
  }

  /** Assert that the actual poll message matches the expected one, ignoring IDs. */
  public static void assertPollMessagesEqual(PollMessage actual, PollMessage expected) {
    assertThat(POLL_MESSAGE_ID_STRIPPER.apply(actual))
        .isEqualTo(POLL_MESSAGE_ID_STRIPPER.apply(expected));
  }

  /** Assert that the actual poll messages match the expected ones, ignoring IDs and order. */
  public static void assertPollMessagesEqual(
      Iterable<PollMessage> actual, Iterable<PollMessage> expected) {
    assertThat(Streams.stream(actual).map(POLL_MESSAGE_ID_STRIPPER).collect(toImmutableList()))
        .containsExactlyElementsIn(
            Streams.stream(expected).map(POLL_MESSAGE_ID_STRIPPER).collect(toImmutableList()));
  }

  public static void assertPollMessagesForResource(EppResource resource, PollMessage... expected) {
    assertThat(
            getPollMessages(resource)
                .stream()
                .map(POLL_MESSAGE_ID_STRIPPER)
                .collect(toImmutableList()))
        .containsExactlyElementsIn(
            Arrays.stream(expected).map(POLL_MESSAGE_ID_STRIPPER).collect(toImmutableList()));
  }

  /** Helper to effectively erase the poll message ID to facilitate comparison. */
  public static final Function<PollMessage, PollMessage> POLL_MESSAGE_ID_STRIPPER =
      pollMessage -> pollMessage.asBuilder().setId(1L).build();

  public static ImmutableList<PollMessage> getPollMessages() {
    return Streams.stream(ofy().load().type(PollMessage.class)).collect(toImmutableList());
  }

  public static ImmutableList<PollMessage> getPollMessages(String clientId) {
    return Streams.stream(ofy().load().type(PollMessage.class).filter("clientId", clientId))
        .collect(toImmutableList());
  }

  public static ImmutableList<PollMessage> getPollMessages(EppResource resource) {
    return Streams.stream(ofy().load().type(PollMessage.class).ancestor(resource))
        .collect(toImmutableList());
  }

  public static ImmutableList<PollMessage> getPollMessages(String clientId, DateTime now) {
    return Streams.stream(
            ofy()
                .load()
                .type(PollMessage.class)
                .filter("clientId", clientId)
                .filter("eventTime <=", now.toDate()))
        .collect(toImmutableList());
  }

  /** Gets all PollMessages associated with the given EppResource. */
  public static ImmutableList<PollMessage> getPollMessages(
      EppResource resource, String clientId, DateTime now) {
    return Streams.stream(
            ofy()
                .load()
                .type(PollMessage.class)
                .ancestor(resource)
                .filter("clientId", clientId)
                .filter("eventTime <=", now.toDate()))
        .collect(toImmutableList());
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
    return getPollMessages(clientId, now)
        .stream()
        .filter(subType::isInstance)
        .map(subType::cast)
        .collect(onlyElement());
  }

  public static PollMessage getOnlyPollMessage(
      EppResource resource,
      String clientId,
      DateTime now,
      Class<? extends PollMessage> subType) {
    return getPollMessages(resource, clientId, now)
        .stream()
        .filter(subType::isInstance)
        .map(subType::cast)
        .collect(onlyElement());
  }

  /** Returns a newly allocated, globally unique domain repoId of the format HEX-TLD. */
  public static String generateNewDomainRoid(String tld) {
    return createDomainRepoId(ObjectifyService.allocateId(), tld);
  }

  /**
   * Returns a newly allocated, globally unique contact/host repoId of the format
   * HEX_TLD-ROID.
   */
  public static String generateNewContactHostRoid() {
    return createRepoId(ObjectifyService.allocateId(), getContactAndHostRoidSuffix());
  }

  /**
   * Persists a test resource to Datastore and returns it.
   *
   * <p>Tests should always use this method (or the shortcut persist methods in this class) to
   * persist test data, to avoid potentially subtle bugs related to race conditions and a stale
   * ofy() session cache. Specifically, this method calls .now() on the save to force the write to
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
    Saver saver = wantBackup ? ofy().save() : ofy().saveWithoutBackup();
    saver.entity(resource);
    if (resource instanceof EppResource) {
      EppResource eppResource = (EppResource) resource;
      persistEppResourceExtras(
          eppResource, EppResourceIndex.create(Key.create(eppResource)), saver);
    }
  }

  private static <R extends EppResource> void persistEppResourceExtras(
      R resource, EppResourceIndex index, Saver saver) {
    assertWithMessage("Cannot persist an EppResource with a missing repoId in tests")
        .that(resource.getRepoId())
        .isNotEmpty();
    saver.entity(index);
    if (resource instanceof ForeignKeyedEppResource) {
      saver.entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
    }
  }

  private static <R> R persistResource(final R resource, final boolean wantBackup) {
    assertWithMessage("Attempting to persist a Builder is almost certainly an error in test code")
        .that(resource)
        .isNotInstanceOf(Buildable.Builder.class);
    tm().transact(() -> saveResource(resource, wantBackup));
    // Force the session cache to be cleared so that when we read the resource back, we read from
    // Datastore and not from the session cache. This is needed to trigger Objectify's load process
    // (unmarshalling entity protos to POJOs, nulling out empty collections, calling @OnLoad
    // methods, etc.) which is bypassed for entities loaded from the session cache.
    ofy().clearSessionCache();
    return ofy().load().entity(resource).now();
  }

  /** Persists an EPP resource with the {@link EppResourceIndex} always going into bucket one. */
  public static <R extends EppResource> R persistEppResourceInFirstBucket(final R resource) {
    final EppResourceIndex eppResourceIndex =
        EppResourceIndex.create(Key.create(EppResourceIndexBucket.class, 1), Key.create(resource));
    tm()
        .transact(
            () -> {
              Saver saver = ofy().save();
              saver.entity(resource);
              persistEppResourceExtras(resource, eppResourceIndex, saver);
            });
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
      tm().transact(() -> chunk.forEach(resource -> saveResource(resource, wantBackup)));
    }
    // Force the session to be cleared so that when we read it back, we read from Datastore
    // and not from the transaction's session cache.
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
    checkState(!tm().inTransaction());
    tm()
        .transact(
            () -> {
              ofy()
                  .save()
                  .<ImmutableObject>entities(
                      resource,
                      new HistoryEntry.Builder()
                          .setParent(resource)
                          .setType(getHistoryEntryType(resource))
                          .setModificationTime(tm().getTransactionTime())
                          .build());
              ofy().save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
            });
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
  public static ImmutableList<HistoryEntry> getHistoryEntriesOfType(
      EppResource resource, final HistoryEntry.Type type) {
    return getHistoryEntries(resource)
        .stream()
        .filter(entry -> entry.getType() == type)
        .collect(toImmutableList());
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
    } else if (resource instanceof DomainBase) {
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
   */
  public static <R> ImmutableList<R> persistSimpleResources(final Iterable<R> resources) {
    tm().transact(() -> ofy().saveWithoutBackup().entities(resources));
    // Force the session to be cleared so that when we read it back, we read from Datastore
    // and not from the transaction's session cache.
    ofy().clearSessionCache();
    return ImmutableList.copyOf(ofy().load().entities(resources).values());
  }

  public static void deleteResource(final Object resource) {
    ofy().deleteWithoutBackup().entity(resource).now();
    // Force the session to be cleared so that when we read it back, we read from Datastore and
    // not from the transaction's session cache.
    ofy().clearSessionCache();
  }

  /** Force the create and update timestamps to get written into the resource. **/
  public static <R> R cloneAndSetAutoTimestamps(final R resource) {
    return tm().transact(() -> ofy().load().fromEntity(ofy().save().toEntity(resource)));
  }

  /** Returns the entire map of {@link PremiumListEntry}s for the given {@link PremiumList}. */
  public static ImmutableMap<String, PremiumListEntry> loadPremiumListEntries(
      PremiumList premiumList) {
    try {
      ImmutableMap.Builder<String, PremiumListEntry> entriesMap = new ImmutableMap.Builder<>();
      if (premiumList.getRevisionKey() != null) {
        for (PremiumListEntry entry :
            ofy().load().type(PremiumListEntry.class).ancestor(premiumList.getRevisionKey())) {
          entriesMap.put(entry.getLabel(), entry);
        }
      }
      return entriesMap.build();
    } catch (Exception e) {
      throw new RuntimeException(
          "Could not retrieve entries for premium list " + premiumList.getName(), e);
    }
  }

  /** Loads and returns the registrar with the given client ID, or throws IAE if not present. */
  public static Registrar loadRegistrar(String clientId) {
    return checkArgumentPresent(
        Registrar.loadByClientId(clientId),
        "Error in tests: Registrar %s does not exist",
        clientId);
  }

  private DatastoreHelper() {}
}
