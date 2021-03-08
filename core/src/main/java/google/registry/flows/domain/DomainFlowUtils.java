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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.collect.Sets.union;
import static google.registry.model.DatabaseMigrationUtils.getPrimaryDatabase;
import static google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase.DATASTORE;
import static google.registry.model.common.DatabaseTransitionSchedule.TransitionId.REPLAYED_ENTITIES;
import static google.registry.model.domain.DomainBase.MAX_REGISTRATION_YEARS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.model.registry.Registries.getTlds;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.PREDELEGATION;
import static google.registry.model.registry.Registry.TldState.QUIET_PERIOD;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.model.registry.label.ReservationType.ALLOWED_IN_SUNRISE;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.model.registry.label.ReservationType.NAME_COLLISION;
import static google.registry.model.registry.label.ReservationType.RESERVED_FOR_ANCHOR_TENANT;
import static google.registry.model.registry.label.ReservationType.RESERVED_FOR_SPECIFIC_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;
import static google.registry.util.DomainNameUtils.ACE_PREFIX;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.model.EppResource;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainCommand.CreateOrUpdate;
import google.registry.model.domain.DomainCommand.InvalidReferencesException;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.ForeignKeyedDesignatedContact;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.domain.fee.FeeQueryResponseExtensionItem;
import google.registry.model.domain.fee.FeeTransformCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchExtension;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.launch.LaunchNotice.InvalidChecksumException;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsInfoExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Add;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Remove;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservationType;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.persistence.VKey;
import google.registry.tldconfig.idn.IdnLabelValidator;
import google.registry.util.Idn;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Static utility functions for domain flows. */
public class DomainFlowUtils {

  /** Map from launch phases to the allowed tld states. */
  private static final ImmutableMultimap<LaunchPhase, TldState> LAUNCH_PHASE_TO_TLD_STATES =
      new ImmutableMultimap.Builder<LaunchPhase, TldState>()
          .putAll(LaunchPhase.CLAIMS, GENERAL_AVAILABILITY, QUIET_PERIOD)
          .put(LaunchPhase.SUNRISE, START_DATE_SUNRISE)
          .put(LaunchPhase.OPEN, GENERAL_AVAILABILITY)
          .build();

  /** Reservation types that are only allowed in sunrise by policy. */
  public static final ImmutableSet<ReservationType> TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE =
      Sets.immutableEnumSet(ALLOWED_IN_SUNRISE, NAME_COLLISION);

  /** Warning message for allocation of collision domains in sunrise. */
  public static final String COLLISION_MESSAGE =
      "Domain on the name collision list was allocated. But by policy, the domain will not be "
          + "delegated. Please visit https://www.icann.org/namecollision  for more information on "
          + "name collision.";

  /** Strict validator for ascii lowercase letters, digits, and "-", allowing "." as a separator */
  private static final CharMatcher ALLOWED_CHARS =
      CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('0', '9').or(CharMatcher.anyOf("-.")));

  /** Default validator used to determine if an IDN name can be provisioned on a TLD. */
  private static final IdnLabelValidator IDN_LABEL_VALIDATOR =
      IdnLabelValidator.createDefaultIdnLabelValidator();

  /** The maximum number of DS records allowed on a domain. */
  private static final int MAX_DS_RECORDS_PER_DOMAIN = 8;

  /** Maximum number of nameservers allowed per domain. */
  private static final int MAX_NAMESERVERS_PER_DOMAIN = 13;

  /** Maximum number of characters in a domain label, from RFC 2181. */
  private static final int MAX_LABEL_SIZE = 63;

  /**
   * Returns parsed version of {@code name} if domain name label follows our naming rules and is
   * under one of the given allowed TLDs.
   *
   * <p><b>Note:</b> This method does not perform language validation with IDN tables.
   *
   * @see #validateDomainNameWithIdnTables(InternetDomainName)
   */
  public static InternetDomainName validateDomainName(String name) throws EppException {
    if (!ALLOWED_CHARS.matchesAllOf(name)) {
      throw new BadDomainNameCharacterException();
    }
    List<String> parts = Splitter.on('.').splitToList(name);
    if (parts.size() <= 1) {
      throw new BadDomainNamePartsCountException();
    }
    if (any(parts, equalTo(""))) {
      throw new EmptyDomainNamePartException();
    }
    validateFirstLabel(parts.get(0));
    InternetDomainName domainName = InternetDomainName.from(name);
    if (getTlds().contains(domainName.toString())) {
      throw new DomainNameExistsAsTldException();
    }
    Optional<InternetDomainName> tldParsed = findTldForName(domainName);
    if (!tldParsed.isPresent()) {
      throw new TldDoesNotExistException(domainName.parent().toString());
    }
    if (domainName.parts().size() != tldParsed.get().parts().size() + 1) {
      throw new BadDomainNamePartsCountException();
    }
    return domainName;
  }

  private static void validateFirstLabel(String firstLabel) throws EppException {
    if (firstLabel.length() > MAX_LABEL_SIZE) {
      throw new DomainLabelTooLongException();
    }
    if (firstLabel.startsWith("-")) {
      throw new LeadingDashException();
    }
    if (firstLabel.endsWith("-")) {
      throw new TrailingDashException();
    }
    String unicode = Idn.toUnicode(firstLabel);
    if (firstLabel.startsWith(ACE_PREFIX) && firstLabel.equals(unicode)) {
      throw new InvalidPunycodeException();
    }
    if (!firstLabel.startsWith(ACE_PREFIX)
        && firstLabel.length() >= 4
        && firstLabel.substring(2).startsWith("--")) {
      throw new DashesInThirdAndFourthException();
    }
  }

  /**
   * Returns name of first matching IDN table for domain label.
   *
   * @throws InvalidIdnDomainLabelException if IDN table or language validation failed
   * @see #validateDomainName(String)
   */
  public static String validateDomainNameWithIdnTables(InternetDomainName domainName)
      throws InvalidIdnDomainLabelException {
    Optional<String> idnTableName =
        IDN_LABEL_VALIDATOR.findValidIdnTableForTld(
            domainName.parts().get(0), domainName.parent().toString());
    if (!idnTableName.isPresent()) {
      throw new InvalidIdnDomainLabelException();
    }
    return idnTableName.get();
  }

  /** Returns whether a given domain create request is for a valid anchor tenant. */
  public static boolean isAnchorTenant(
      InternetDomainName domainName,
      Optional<AllocationToken> token,
      Optional<MetadataExtension> metadataExtension) {
    // If the domain is reserved for anchor tenants, then check if the allocation token exists and
    // is for this domain.
    if (getReservationTypes(domainName).contains(RESERVED_FOR_ANCHOR_TENANT)
        && token.isPresent()
        && token.get().getDomainName().isPresent()
        && token.get().getDomainName().get().equals(domainName.toString())) {
      return true;
    }
    // Otherwise check whether the metadata extension is being used by a superuser to specify that
    // it's an anchor tenant creation.
    return metadataExtension.isPresent() && metadataExtension.get().getIsAnchorTenant();
  }

  /** Returns whether a given domain create request is for a valid reserved domain. */
  public static boolean isValidReservedCreate(
      InternetDomainName domainName, Optional<AllocationToken> token) {
    // If the domain is reserved for specific use, then check if the allocation token exists and
    // is for this domain.
    return getReservationTypes(domainName).contains(RESERVED_FOR_SPECIFIC_USE)
        && token.isPresent()
        && token.get().getDomainName().isPresent()
        && token.get().getDomainName().get().equals(domainName.toString());
  }

  /** Check if the registrar running the flow has access to the TLD in question. */
  public static void checkAllowedAccessToTld(String clientId, String tld) throws EppException {
    if (!Registrar.loadByClientIdCached(clientId).get().getAllowedTlds().contains(tld)) {
      throw new DomainFlowUtils.NotAuthorizedForTldException(tld);
    }
  }

  /** Check that the DS data that will be set on a domain is valid. */
  static void validateDsData(Set<DelegationSignerData> dsData) throws EppException {
    if (dsData != null && dsData.size() > MAX_DS_RECORDS_PER_DOMAIN) {
      throw new TooManyDsRecordsException(
          String.format(
              "A maximum of %s DS records are allowed per domain.", MAX_DS_RECORDS_PER_DOMAIN));
    }
  }

  /** We only allow specifying years in a period. */
  static Period verifyUnitIsYears(Period period) throws EppException {
    if (!checkNotNull(period).getUnit().equals(Period.Unit.YEARS)) {
      throw new BadPeriodUnitException();
    }
    return period;
  }

  /** Verify that no linked resources have disallowed statuses. */
  static void verifyNotInPendingDelete(
      Set<DesignatedContact> contacts,
      VKey<ContactResource> registrant,
      Set<VKey<HostResource>> nameservers)
      throws EppException {
    ImmutableList.Builder<VKey<? extends EppResource>> keysToLoad = new ImmutableList.Builder<>();
    contacts.stream().map(DesignatedContact::getContactKey).forEach(keysToLoad::add);
    Optional.ofNullable(registrant).ifPresent(keysToLoad::add);
    keysToLoad.addAll(nameservers);
    verifyNotInPendingDelete(EppResource.loadCached(keysToLoad.build()).values());
  }

  private static void verifyNotInPendingDelete(Iterable<EppResource> resources)
      throws EppException {
    for (EppResource resource : resources) {
      if (resource.getStatusValues().contains(StatusValue.PENDING_DELETE)) {
        throw new LinkedResourceInPendingDeleteProhibitsOperationException(
            resource.getForeignKey());
      }
    }
  }

  static void validateContactsHaveTypes(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    for (DesignatedContact contact : contacts) {
      if (contact.getType() == null) {
        throw new MissingContactTypeException();
      }
    }
  }

  static void validateNameserversCountForTld(String tld, InternetDomainName domainName, int count)
      throws EppException {
    // For TLDs with a nameserver allow list, all domains must have at least 1 nameserver.
    ImmutableSet<String> tldNameserversAllowList =
        Registry.get(tld).getAllowedFullyQualifiedHostNames();
    if (!tldNameserversAllowList.isEmpty() && count == 0) {
      throw new NameserversNotSpecifiedForTldWithNameserverAllowListException(
          domainName.toString());
    }
    if (count > MAX_NAMESERVERS_PER_DOMAIN) {
      throw new TooManyNameserversException(
          String.format("Only %d nameservers are allowed per domain", MAX_NAMESERVERS_PER_DOMAIN));
    }
  }

  static void validateNoDuplicateContacts(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    ImmutableMultimap<Type, VKey<ContactResource>> contactsByType =
        contacts.stream()
            .collect(
                toImmutableSetMultimap(
                    DesignatedContact::getType, contact -> contact.getContactKey()));

    // If any contact type has multiple contacts:
    if (contactsByType.asMap().values().stream().anyMatch(v -> v.size() > 1)) {
      // Find the duplicates.
      Map<Type, Collection<VKey<ContactResource>>> dupeKeysMap =
          Maps.filterEntries(contactsByType.asMap(), e -> e.getValue().size() > 1);
      ImmutableList<VKey<ContactResource>> dupeKeys =
          dupeKeysMap.values().stream().flatMap(Collection::stream).collect(toImmutableList());
      // Load the duplicates in one batch.
      Map<VKey<? extends ContactResource>, ContactResource> dupeContacts =
          tm().loadByKeys(dupeKeys);
      ImmutableMultimap.Builder<Type, VKey<ContactResource>> typesMap =
          new ImmutableMultimap.Builder<>();
      dupeKeysMap.forEach(typesMap::putAll);
      // Create an error message showing the type and contact IDs of the duplicates.
      throw new DuplicateContactForRoleException(
          Multimaps.transformValues(typesMap.build(), key -> dupeContacts.get(key).getContactId()));
    }
  }

  static void validateRequiredContactsPresent(
      @Nullable VKey<ContactResource> registrant, Set<DesignatedContact> contacts)
      throws RequiredParameterMissingException {
    if (registrant == null) {
      throw new MissingRegistrantException();
    }

    Set<Type> roles = new HashSet<>();
    for (DesignatedContact contact : contacts) {
      roles.add(contact.getType());
    }
    if (!roles.contains(Type.ADMIN)) {
      throw new MissingAdminContactException();
    }
    if (!roles.contains(Type.TECH)) {
      throw new MissingTechnicalContactException();
    }
  }

  static void validateRegistrantAllowedOnTld(String tld, String registrantContactId)
      throws RegistrantNotAllowedException {
    ImmutableSet<String> allowedRegistrants = Registry.get(tld).getAllowedRegistrantContactIds();
    // Empty allow list or null registrantContactId are ignored.
    if (registrantContactId != null
        && !allowedRegistrants.isEmpty()
        && !allowedRegistrants.contains(registrantContactId)) {
      throw new RegistrantNotAllowedException(registrantContactId);
    }
  }

  static void validateNameserversAllowedOnTld(String tld, Set<String> fullyQualifiedHostNames)
      throws EppException {
    ImmutableSet<String> allowedHostNames = Registry.get(tld).getAllowedFullyQualifiedHostNames();
    Set<String> hostnames = nullToEmpty(fullyQualifiedHostNames);
    if (!allowedHostNames.isEmpty()) { // Empty allow list is ignored.
      Set<String> disallowedNameservers = difference(hostnames, allowedHostNames);
      if (!disallowedNameservers.isEmpty()) {
        throw new NameserversNotAllowedForTldException(disallowedNameservers);
      }
    }
  }

  static void verifyNotReserved(InternetDomainName domainName, boolean isSunrise)
      throws EppException {
    if (isReserved(domainName, isSunrise)) {
      throw new DomainReservedException(domainName.toString());
    }
  }

  private static final ImmutableSet<ReservationType> RESERVED_TYPES =
      ImmutableSet.of(RESERVED_FOR_SPECIFIC_USE, RESERVED_FOR_ANCHOR_TENANT, FULLY_BLOCKED);

  static boolean isReserved(InternetDomainName domainName, boolean isSunrise) {
    ImmutableSet<ReservationType> types = getReservationTypes(domainName);
    return !Sets.intersection(types, RESERVED_TYPES).isEmpty()
        || !(isSunrise || intersection(TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE, types).isEmpty());
  }

  /** Returns a set of {@link ReservationType}s for the given domain name. */
  static ImmutableSet<ReservationType> getReservationTypes(InternetDomainName domainName) {
    // The TLD should always be the parent of the requested domain name.
    return ReservedList.getReservationTypes(
        domainName.parts().get(0), domainName.parent().toString());
  }

  /** Verifies that a launch extension's specified phase matches the specified registry's phase. */
  static void verifyLaunchPhaseMatchesRegistryPhase(
      Registry registry, LaunchExtension launchExtension, DateTime now) throws EppException {
    if (!LAUNCH_PHASE_TO_TLD_STATES.containsKey(launchExtension.getPhase())
        || !LAUNCH_PHASE_TO_TLD_STATES
            .get(launchExtension.getPhase())
            .contains(registry.getTldState(now))) {
      // No launch operations are allowed during the quiet period or predelegation.
      throw new LaunchPhaseMismatchException();
    }
  }

  /**
   * Verifies that a domain name is allowed to be delegated to the given client id. The only case
   * where it would not be allowed is if domain name is premium, and premium names are blocked by
   * this registrar.
   */
  static void verifyPremiumNameIsNotBlocked(String domainName, DateTime priceTime, String clientId)
      throws EppException {
    if (isDomainPremium(domainName, priceTime)) {
      if (Registrar.loadByClientIdCached(clientId).get().getBlockPremiumNames()) {
        throw new PremiumNameBlockedException();
      }
    }
  }

  /**
   * Helper to call {@link CreateOrUpdate#cloneAndLinkReferences} and convert exceptions to
   * EppExceptions, since this is needed in several places.
   */
  static <T extends CreateOrUpdate<T>> T cloneAndLinkReferences(T command, DateTime now)
      throws EppException {
    try {
      return command.cloneAndLinkReferences(now);
    } catch (InvalidReferencesException e) {
      throw new LinkedResourcesDoNotExistException(e.getType(), e.getForeignKeys());
    }
  }

  /**
   * Fills in a builder with the data needed for an autorenew billing event for this domain. This
   * does not copy over the id of the current autorenew billing event.
   */
  public static BillingEvent.Recurring.Builder newAutorenewBillingEvent(DomainBase domain) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime());
  }

  /**
   * Fills in a builder with the data needed for an autorenew poll message for this domain. This
   * does not copy over the id of the current autorenew poll message.
   */
  public static PollMessage.Autorenew.Builder newAutorenewPollMessage(DomainBase domain) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime())
        .setMsg("Domain was auto-renewed.");
  }

  /**
   * Re-saves the current autorenew billing event and poll message with a new end time.
   *
   * <p>This may end up deleting the poll message (if closing the message interval) or recreating it
   * (if opening the message interval). This may cause an autorenew billing event to have an end
   * time earlier than its event time (i.e. if it's being ended before it was ever triggered).
   */
  public static void updateAutorenewRecurrenceEndTime(DomainBase domain, DateTime newEndTime) {
    Optional<PollMessage.Autorenew> autorenewPollMessage =
        tm().loadByKeyIfPresent(domain.getAutorenewPollMessage());

    // Construct an updated autorenew poll message. If the autorenew poll message no longer exists,
    // create a new one at the same id. This can happen if a transfer was requested on a domain
    // where all autorenew poll messages had already been delivered (this would cause the poll
    // message to be deleted), and then subsequently the transfer was canceled, rejected, or deleted
    // (which would cause the poll message to be recreated here).
    Key<PollMessage.Autorenew> existingAutorenewKey = domain.getAutorenewPollMessage().getOfyKey();
    PollMessage.Autorenew updatedAutorenewPollMessage =
        autorenewPollMessage.isPresent()
            ? autorenewPollMessage.get().asBuilder().setAutorenewEndTime(newEndTime).build()
            : newAutorenewPollMessage(domain)
                .setId(existingAutorenewKey.getId())
                .setAutorenewEndTime(newEndTime)
                .setParentKey(existingAutorenewKey.getParent())
                .build();

    // If the resultant autorenew poll message would have no poll messages to deliver, then just
    // delete it. Otherwise save it with the new end time.
    if (isAtOrAfter(updatedAutorenewPollMessage.getEventTime(), newEndTime)) {
      autorenewPollMessage.ifPresent(autorenew -> tm().delete(autorenew));
    } else {
      tm().put(updatedAutorenewPollMessage);
    }

    Recurring recurring = tm().loadByKey(domain.getAutorenewBillingEvent());
    tm().put(recurring.asBuilder().setRecurrenceEndTime(newEndTime).build());
  }

  /**
   * Validates a {@link FeeQueryCommandExtensionItem} and sets the appropriate fields on a {@link
   * FeeQueryResponseExtensionItem} builder.
   */
  static void handleFeeRequest(
      FeeQueryCommandExtensionItem feeRequest,
      FeeQueryResponseExtensionItem.Builder<?, ?> builder,
      InternetDomainName domainName,
      Optional<DomainBase> domain,
      @Nullable CurrencyUnit topLevelCurrency,
      DateTime currentDate,
      DomainPricingLogic pricingLogic,
      Optional<AllocationToken> allocationToken,
      boolean isAvailable)
      throws EppException {
    DateTime now = currentDate;
    // Use the custom effective date specified in the fee check request, if there is one.
    if (feeRequest.getEffectiveDate().isPresent()) {
      now = feeRequest.getEffectiveDate().get();
      builder.setEffectiveDateIfSupported(now);
    }
    String domainNameString = domainName.toString();
    Registry registry = Registry.get(domainName.parent().toString());
    int years = verifyUnitIsYears(feeRequest.getPeriod()).getValue();
    boolean isSunrise = (registry.getTldState(now) == START_DATE_SUNRISE);

    if (feeRequest.getPhase() != null || feeRequest.getSubphase() != null) {
      throw new FeeChecksDontSupportPhasesException();
    }

    CurrencyUnit currency =
        feeRequest.getCurrency() != null ? feeRequest.getCurrency() : topLevelCurrency;
    if ((currency != null) && !currency.equals(registry.getCurrency())) {
      throw new CurrencyUnitMismatchException();
    }

    builder
        .setCommand(feeRequest.getCommandName(), feeRequest.getPhase(), feeRequest.getSubphase())
        .setCurrencyIfSupported(registry.getCurrency())
        .setPeriod(feeRequest.getPeriod());

    String feeClass = null;
    ImmutableList<Fee> fees = ImmutableList.of();
    switch (feeRequest.getCommandName()) {
      case CREATE:
        // Don't return a create price for reserved names.
        if (isReserved(domainName, isSunrise) && !isAvailable) {
          feeClass = "reserved";
          builder.setAvailIfSupported(false);
          builder.setReasonIfSupported("reserved");
        } else {
          builder.setAvailIfSupported(true);
          fees =
              pricingLogic
                  .getCreatePrice(registry, domainNameString, now, years, false, allocationToken)
                  .getFees();
        }
        break;
      case RENEW:
        builder.setAvailIfSupported(true);
        fees = pricingLogic.getRenewPrice(registry, domainNameString, now, years).getFees();
        break;
      case RESTORE:
        // The minimum allowable period per the EPP spec is 1, so, strangely, 1 year still has to be
        // passed in as the period for a restore even if the domain would *not* be renewed as part
        // of a restore. This is fixed in RFC 8748 (which is a more recent version of the fee
        // extension than we currently support), which does not allow the period to be passed at all
        // on a restore fee check.
        if (years != 1) {
          throw new RestoresAreAlwaysForOneYearException();
        }
        builder.setAvailIfSupported(true);
        // Domains that never existed, or that used to exist but have completed the entire deletion
        // process, don't count as expired for the purposes of requiring an added year of renewal on
        // restore because they can't be restored in the first place.
        boolean isExpired =
            domain.isPresent() && domain.get().getRegistrationExpirationTime().isBefore(now);
        fees = pricingLogic.getRestorePrice(registry, domainNameString, now, isExpired).getFees();
        break;
      case TRANSFER:
        if (years != 1) {
          throw new TransfersAreAlwaysForOneYearException();
        }
        builder.setAvailIfSupported(true);
        fees = pricingLogic.getTransferPrice(registry, domainNameString, now).getFees();
        break;
      case UPDATE:
        builder.setAvailIfSupported(true);
        fees = pricingLogic.getUpdatePrice(registry, domainNameString, now).getFees();
        break;
      default:
        throw new UnknownFeeCommandException(feeRequest.getUnparsedCommandName());
    }

    if (feeClass == null) {
      // Calculate and set the correct fee class based on whether the name is a collision name or we
      // are returning any premium fees, but only if the fee class isn't already set (i.e. because
      // the domain is reserved, which overrides any other classes).
      boolean isNameCollisionInSunrise =
          registry.getTldState(now).equals(START_DATE_SUNRISE)
              && getReservationTypes(domainName).contains(NAME_COLLISION);
      boolean isPremium = fees.stream().anyMatch(BaseFee::isPremium);
      feeClass =
          emptyToNull(
              Joiner.on('-')
                  .skipNulls()
                  .join(
                      isPremium ? "premium" : null, isNameCollisionInSunrise ? "collision" : null));
    }
    builder.setClass(feeClass);

    // Set the fees, and based on the validDateRange of the fees, set the notAfterDate.
    if (!fees.isEmpty()) {
      builder.setFees(fees);
      DateTime notAfterDate = null;
      for (Fee fee : fees) {
        if (fee.hasValidDateRange()) {
          DateTime endDate = fee.getValidDateRange().upperEndpoint();
          if (notAfterDate == null || notAfterDate.isAfter(endDate)) {
            notAfterDate = endDate;
          }
        }
      }
      if (notAfterDate != null && !notAfterDate.equals(END_OF_TIME)) {
        builder.setNotAfterDateIfSupported(notAfterDate);
      }
    }
  }

  /**
   * Validates that fees are acked and match if they are required (typically for premium domains).
   *
   * <p>This is used by domain operations that have an implicit cost, e.g. domain create or renew
   * (both of which add one or more years' worth of registration). Depending on registry and/or
   * registrar settings, explicit price acking using the fee extension may be required for premium
   * domain names.
   */
  public static void validateFeeChallenge(
      String domainName,
      DateTime priceTime,
      final Optional<? extends FeeTransformCommandExtension> feeCommand,
      FeesAndCredits feesAndCredits)
      throws EppException {
    if (isDomainPremium(domainName, priceTime) && !feeCommand.isPresent()) {
      throw new FeesRequiredForPremiumNameException();
    }
    validateFeesAckedIfPresent(feeCommand, feesAndCredits);
  }

  /**
   * Validates that non-zero fees are acked (i.e. they are specified and the amount matches).
   *
   * <p>This is used directly by update operations, i.e. those that otherwise don't have implicit
   * costs, and is also used as a helper method to validate if fees are required for operations that
   * do have implicit costs, e.g. creates and renews.
   */
  public static void validateFeesAckedIfPresent(
      final Optional<? extends FeeTransformCommandExtension> feeCommand,
      FeesAndCredits feesAndCredits)
      throws EppException {
    // Check for the case where a fee command extension was required but not provided.
    // This only happens when the total fees are non-zero and include custom fees requiring the
    // extension.
    if (!feeCommand.isPresent()) {
      if (!feesAndCredits.getEapCost().isZero()) {
        throw new FeesRequiredDuringEarlyAccessProgramException(feesAndCredits.getEapCost());
      }
      if (feesAndCredits.getTotalCost().isZero() || !feesAndCredits.isFeeExtensionRequired()) {
        return;
      }
      throw new FeesRequiredForNonFreeOperationException(feesAndCredits.getTotalCost());
    }

    List<Fee> fees = feeCommand.get().getFees();
    // The schema guarantees that at least one fee will be present.
    checkState(!fees.isEmpty());
    BigDecimal total = zeroInCurrency(feeCommand.get().getCurrency());
    for (Fee fee : fees) {
      if (!fee.hasDefaultAttributes()) {
        throw new UnsupportedFeeAttributeException();
      }
      total = total.add(fee.getCost());
    }
    for (Credit credit : feeCommand.get().getCredits()) {
      if (!credit.hasDefaultAttributes()) {
        throw new UnsupportedFeeAttributeException();
      }
      total = total.add(credit.getCost());
    }

    Money feeTotal;
    try {
      feeTotal = Money.of(feeCommand.get().getCurrency(), total);
    } catch (ArithmeticException e) {
      throw new CurrencyValueScaleException();
    }

    if (!feeTotal.getCurrencyUnit().equals(feesAndCredits.getCurrency())) {
      throw new CurrencyUnitMismatchException();
    }
    // If more than one fees are required, always validate individual fees.
    ImmutableMap<FeeType, Money> expectedFeeMap =
        buildFeeMap(feesAndCredits.getFees(), feesAndCredits.getCurrency());
    if (expectedFeeMap.size() > 1) {
      ImmutableMap<FeeType, Money> providedFeeMap =
          buildFeeMap(feeCommand.get().getFees(), feeCommand.get().getCurrency());
      for (FeeType type : expectedFeeMap.keySet()) {
        if (!providedFeeMap.containsKey(type)) {
          throw new FeesMismatchException(type);
        }
        Money expectedCost = expectedFeeMap.get(type);
        if (!providedFeeMap.get(type).isEqual(expectedCost)) {
          throw new FeesMismatchException(type, expectedCost);
        }
      }
    }
    // Checking if total amount is expected. Extra fees that we are not expecting may be passed in.
    // Or if there is only a single fee type expected.
    if (!feeTotal.equals(feesAndCredits.getTotalCost())) {
      throw new FeesMismatchException(feesAndCredits.getTotalCost());
    }
  }

  private static FeeType getOrParseType(Fee fee) throws ParameterValuePolicyErrorException {
    if (fee.getType() != null) {
      return fee.getType();
    }
    ImmutableList<FeeType> types = fee.parseDescriptionForTypes();
    if (types.size() == 0) {
      throw new FeeDescriptionParseException(fee.getDescription());
    } else if (types.size() > 1) {
      throw new FeeDescriptionMultipleMatchesException(fee.getDescription(), types);
    } else {
      return types.get(0);
    }
  }

  private static ImmutableMap<FeeType, Money> buildFeeMap(List<Fee> fees, CurrencyUnit currency)
      throws ParameterValuePolicyErrorException {
    ImmutableMultimap.Builder<FeeType, Money> mapBuilder =
        new ImmutableMultimap.Builder<FeeType, Money>().orderKeysBy(Comparator.naturalOrder());
    for (Fee fee : fees) {
      mapBuilder.put(getOrParseType(fee), Money.of(currency, fee.getCost()));
    }
    return mapBuilder
        .build()
        .asMap()
        .entrySet()
        .stream()
        .collect(toImmutableMap(Entry::getKey, entry -> Money.total(entry.getValue())));
  }

  /**
   * Check whether a new expiration time (via a renew) does not extend beyond a maximum number of
   * years (e.g. {@link DomainBase#MAX_REGISTRATION_YEARS}) from "now".
   *
   * @throws ExceedsMaxRegistrationYearsException if the new registration period is too long
   */
  public static void validateRegistrationPeriod(DateTime now, DateTime newExpirationTime)
      throws EppException {
    if (leapSafeAddYears(now, MAX_REGISTRATION_YEARS).isBefore(newExpirationTime)) {
      throw new ExceedsMaxRegistrationYearsException();
    }
  }

  /**
   * Check that a new registration period (via a create) does not extend beyond a maximum number of
   * years (e.g. {@link DomainBase#MAX_REGISTRATION_YEARS}).
   *
   * @throws ExceedsMaxRegistrationYearsException if the new registration period is too long
   */
  public static void validateRegistrationPeriod(int years) throws EppException {
    if (years > MAX_REGISTRATION_YEARS) {
      throw new ExceedsMaxRegistrationYearsException();
    }
  }

  /**
   * Adds a secDns extension to a list if the given set of dsData is non-empty.
   *
   * <p>According to RFC 5910 section 2, we should only return this if the client specified the
   * "urn:ietf:params:xml:ns:secDNS-1.1" when logging in. However, this is a "SHOULD" not a "MUST"
   * and we are going to ignore it; clients who don't care about secDNS can just ignore it.
   */
  static void addSecDnsExtensionIfPresent(
      ImmutableList.Builder<ResponseExtension> extensions,
      ImmutableSet<DelegationSignerData> dsData) {
    if (!dsData.isEmpty()) {
      extensions.add(SecDnsInfoExtension.create(dsData));
    }
  }

  /** Update {@link DelegationSignerData} based on an update extension command. */
  static ImmutableSet<DelegationSignerData> updateDsData(
      ImmutableSet<DelegationSignerData> oldDsData, SecDnsUpdateExtension secDnsUpdate)
      throws EppException {
    // We don't support 'urgent' because we do everything as fast as we can anyways.
    if (Boolean.TRUE.equals(secDnsUpdate.getUrgent())) { // We allow both false and null.
      throw new UrgentAttributeNotSupportedException();
    }
    // There must be at least one of add/rem/chg, and chg isn't actually supported.
    if (secDnsUpdate.getChange() != null) {
      // The only thing you can change is maxSigLife, and we don't support that at all.
      throw new MaxSigLifeChangeNotSupportedException();
    }
    Add add = secDnsUpdate.getAdd();
    Remove remove = secDnsUpdate.getRemove();
    if (add == null && remove == null) {
      throw new EmptySecDnsUpdateException();
    }
    if (remove != null && Boolean.FALSE.equals(remove.getAll())) {
      throw new SecDnsAllUsageException(); // Explicit all=false is meaningless.
    }
    Set<DelegationSignerData> toAdd = (add == null) ? ImmutableSet.of() : add.getDsData();
    Set<DelegationSignerData> toRemove =
        (remove == null)
            ? ImmutableSet.of()
            : (remove.getAll() == null) ? remove.getDsData() : oldDsData;
    // RFC 5910 specifies that removes are processed before adds.
    return ImmutableSet.copyOf(union(difference(oldDsData, toRemove), toAdd));
  }

  /** If a domain "clientUpdateProhibited" set, updates must clear it or fail. */
  static void verifyClientUpdateNotProhibited(Update command, DomainBase existingResource)
      throws ResourceHasClientUpdateProhibitedException {
    if (existingResource.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)
        && !command
            .getInnerRemove()
            .getStatusValues()
            .contains(StatusValue.CLIENT_UPDATE_PROHIBITED)) {
      throw new ResourceHasClientUpdateProhibitedException();
    }
  }

  /**
   * Check that the registrar with the given client ID is active.
   *
   * <p>Non-active registrars are not allowed to run operations that cost money, like domain creates
   * or renews.
   */
  static void verifyRegistrarIsActive(String clientId)
      throws RegistrarMustBeActiveForThisOperationException {
    Registrar registrar = Registrar.loadByClientIdCached(clientId).get();
    if (registrar.getState() != State.ACTIVE) {
      throw new RegistrarMustBeActiveForThisOperationException();
    }
  }

  /** Check that the registry phase is not predelegation, during which some flows are forbidden. */
  public static void verifyNotInPredelegation(Registry registry, DateTime now)
      throws BadCommandForRegistryPhaseException {
    if (registry.getTldState(now) == PREDELEGATION) {
      throw new BadCommandForRegistryPhaseException();
    }
  }

  /** Validate the contacts and nameservers specified in a domain create command. */
  static void validateCreateCommandContactsAndNameservers(
      Create command, Registry registry, InternetDomainName domainName) throws EppException {
    verifyNotInPendingDelete(
        command.getContacts(), command.getRegistrant(), command.getNameservers());
    validateContactsHaveTypes(command.getContacts());
    String tld = registry.getTldStr();
    validateRegistrantAllowedOnTld(tld, command.getRegistrantContactId());
    validateNoDuplicateContacts(command.getContacts());
    validateRequiredContactsPresent(command.getRegistrant(), command.getContacts());
    ImmutableSet<String> fullyQualifiedHostNames = command.getNameserverFullyQualifiedHostNames();
    validateNameserversCountForTld(tld, domainName, fullyQualifiedHostNames.size());
    validateNameserversAllowedOnTld(tld, fullyQualifiedHostNames);
  }

  /** Validate the secDNS extension, if present. */
  static Optional<SecDnsCreateExtension> validateSecDnsExtension(
      Optional<SecDnsCreateExtension> secDnsCreate) throws EppException {
    if (!secDnsCreate.isPresent()) {
      return Optional.empty();
    }
    if (secDnsCreate.get().getDsData() == null) {
      throw new DsDataRequiredException();
    }
    if (secDnsCreate.get().getMaxSigLife() != null) {
      throw new MaxSigLifeNotSupportedException();
    }
    validateDsData(secDnsCreate.get().getDsData());
    return secDnsCreate;
  }

  /** Validate the notice from a launch create extension, allowing null as a valid notice. */
  static void validateLaunchCreateNotice(
      @Nullable LaunchNotice notice, String domainLabel, boolean isSuperuser, DateTime now)
      throws EppException {
    if (notice == null) {
      return;
    }
    if (!notice.getNoticeId().getValidatorId().equals("tmch")) {
      throw new InvalidTrademarkValidatorException();
    }
    // Superuser can force domain creations regardless of the current date.
    if (!isSuperuser) {
      if (notice.getExpirationTime().isBefore(now)) {
        throw new ExpiredClaimException();
      }
      // An acceptance within the past 48 hours is mandated by the TMCH Functional Spec.
      if (notice.getAcceptedTime().isBefore(now.minusHours(48))) {
        throw new AcceptedTooLongAgoException();
      }
    }
    try {
      notice.validate(domainLabel);
    } catch (IllegalArgumentException e) {
      throw new MalformedTcnIdException();
    } catch (InvalidChecksumException e) {
      throw new InvalidTcnIdChecksumException();
    }
  }

  /** Check that the claims period hasn't ended. */
  static void verifyClaimsPeriodNotEnded(Registry registry, DateTime now)
      throws ClaimsPeriodEndedException {
    if (isAtOrAfter(now, registry.getClaimsPeriodEnd())) {
      throw new ClaimsPeriodEndedException(registry.getTldStr());
    }
  }

  /**
   * Returns zero for a specific currency.
   *
   * <p>{@link BigDecimal} has a concept of significant figures, so zero is not always zero. E.g.
   * zero in USD is 0.00, whereas zero in Yen is 0, and zero in Dinars is 0.000 (!).
   */
  static BigDecimal zeroInCurrency(CurrencyUnit currencyUnit) {
    return Money.of(currencyUnit, BigDecimal.ZERO).getAmount();
  }

  /**
   * Check that if there's a claims notice it's on the claims list, and that if there's not one it's
   * not on the claims list.
   */
  static void verifyClaimsNoticeIfAndOnlyIfNeeded(
      InternetDomainName domainName, boolean hasSignedMarks, boolean hasClaimsNotice)
      throws EppException {
    boolean isInClaimsList =
        ClaimsListShard.get().getClaimKey(domainName.parts().get(0)).isPresent();
    if (hasClaimsNotice && !isInClaimsList) {
      throw new UnexpectedClaimsNoticeException(domainName.toString());
    }
    if (!hasClaimsNotice && isInClaimsList && !hasSignedMarks) {
      throw new MissingClaimsNoticeException(domainName.toString());
    }
  }

  /** Check that there are no code marks, which is a type of mark we don't support. */
  static void verifyNoCodeMarks(LaunchCreateExtension launchCreate)
      throws UnsupportedMarkTypeException {
    if (launchCreate.hasCodeMarks()) {
      throw new UnsupportedMarkTypeException();
    }
  }

  /** Create a response extension listing the fees on a domain create. */
  static FeeTransformResponseExtension createFeeCreateResponse(
      FeeTransformCommandExtension feeCreate, FeesAndCredits feesAndCredits) {
    return feeCreate
        .createResponseBuilder()
        .setCurrency(feesAndCredits.getCurrency())
        .setFees(feesAndCredits.getFees())
        .setCredits(feesAndCredits.getCredits())
        .build();
  }

  static ImmutableSet<ForeignKeyedDesignatedContact> loadForeignKeyedDesignatedContacts(
      ImmutableSet<DesignatedContact> contacts) {
    ImmutableSet.Builder<ForeignKeyedDesignatedContact> builder = new ImmutableSet.Builder<>();
    for (DesignatedContact contact : contacts) {
      builder.add(
          ForeignKeyedDesignatedContact.create(
              contact.getType(), tm().loadByKey(contact.getContactKey()).getContactId()));
    }
    return builder.build();
  }

  /**
   * Returns a set of DomainTransactionRecords which negate the most recent HistoryEntry's records.
   *
   * <p>Domain deletes and transfers use this function to account for previous records negated by
   * their flow. For example, if a grace period delete occurs, we must add -1 counters for the
   * associated NET_ADDS_#_YRS field, if it exists.
   *
   * <p>The steps are as follows: 1. Find all HistoryEntries under the domain modified in the past,
   * up to the maxSearchPeriod. 2. Only keep HistoryEntries with a DomainTransactionRecord that a)
   * hasn't been reported yet and b) matches the predicate 3. Return the transactionRecords under
   * the most recent HistoryEntry that fits the above criteria, with negated reportAmounts.
   */
  static ImmutableSet<DomainTransactionRecord> createCancelingRecords(
      DomainBase domainBase,
      final DateTime now,
      Duration maxSearchPeriod,
      final ImmutableSet<TransactionReportField> cancelableFields) {

    List<? extends HistoryEntry> recentHistoryEntries =
        findRecentHistoryEntries(domainBase, now, maxSearchPeriod);
    Optional<? extends HistoryEntry> entryToCancel =
        Streams.findLast(
            recentHistoryEntries.stream()
                .filter(
                    historyEntry -> {
                      // Look for add and renew transaction records that have yet to be reported
                      for (DomainTransactionRecord record :
                          historyEntry.getDomainTransactionRecords()) {
                        if (cancelableFields.contains(record.getReportField())
                            && record.getReportingTime().isAfter(now)) {
                          return true;
                        }
                      }
                      return false;
                    }));
    ImmutableSet.Builder<DomainTransactionRecord> recordsBuilder = new ImmutableSet.Builder<>();
    if (entryToCancel.isPresent()) {
      for (DomainTransactionRecord record : entryToCancel.get().getDomainTransactionRecords()) {
        // Only cancel fields which are cancelable
        if (cancelableFields.contains(record.getReportField())) {
          int cancelledAmount = -1 * record.getReportAmount();
          recordsBuilder.add(record.asBuilder().setReportAmount(cancelledAmount).build());
        }
      }
    }
    return recordsBuilder.build();
  }

  private static List<? extends HistoryEntry> findRecentHistoryEntries(
      DomainBase domainBase, DateTime now, Duration maxSearchPeriod) {
    if (getPrimaryDatabase(REPLAYED_ENTITIES).equals(DATASTORE)) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .ancestor(domainBase)
          .filter("modificationTime >=", now.minus(maxSearchPeriod))
          .order("modificationTime")
          .list();
    } else {
      return jpaTm()
          .getEntityManager()
          .createQuery(
              "FROM DomainHistory WHERE modificationTime >= :beginning "
                  + "ORDER BY modificationTime ASC",
              DomainHistory.class)
          .setParameter("beginning", now.minus(maxSearchPeriod))
          .getResultList();
    }
  }

  /** Resource linked to this domain does not exist. */
  static class LinkedResourcesDoNotExistException extends ObjectDoesNotExistException {
    public LinkedResourcesDoNotExistException(Class<?> type, ImmutableSet<String> resourceIds) {
      super(type, resourceIds);
    }
  }

  /** Linked resource in pending delete prohibits operation. */
  static class LinkedResourceInPendingDeleteProhibitsOperationException
      extends StatusProhibitsOperationException {
    public LinkedResourceInPendingDeleteProhibitsOperationException(String resourceId) {
      super(String.format("Linked resource in pending delete prohibits operation: %s", resourceId));
    }
  }

  /** Domain names can only contain a-z, 0-9, '.' and '-'. */
  static class BadDomainNameCharacterException extends ParameterValuePolicyErrorException {
    public BadDomainNameCharacterException() {
      super("Domain names can only contain a-z, 0-9, '.' and '-'");
    }
  }

  /** Non-IDN domain names cannot contain hyphens in the third or fourth position. */
  static class DashesInThirdAndFourthException extends ParameterValuePolicyErrorException {
    public DashesInThirdAndFourthException() {
      super("Non-IDN domain names cannot contain dashes in the third or fourth position");
    }
  }

  /** Domain labels cannot begin with a dash. */
  static class LeadingDashException extends ParameterValuePolicyErrorException {
    public LeadingDashException() {
      super("Domain labels cannot begin with a dash");
    }
  }

  /** Domain labels cannot end with a dash. */
  static class TrailingDashException extends ParameterValuePolicyErrorException {
    public TrailingDashException() {
      super("Domain labels cannot end with a dash");
    }
  }

  /** Domain labels cannot be longer than 63 characters. */
  static class DomainLabelTooLongException extends ParameterValuePolicyErrorException {
    public DomainLabelTooLongException() {
      super("Domain labels cannot be longer than 63 characters");
    }
  }

  /** No part of a domain name can be empty. */
  static class EmptyDomainNamePartException extends ParameterValuePolicyErrorException {
    public EmptyDomainNamePartException() {
      super("No part of a domain name can be empty");
    }
  }

  /** Domain name starts with xn-- but is not a valid IDN. */
  static class InvalidPunycodeException extends ParameterValuePolicyErrorException {
    public InvalidPunycodeException() {
      super("Domain name starts with xn-- but is not a valid IDN");
    }
  }

  /** Periods for domain registrations must be specified in years. */
  static class BadPeriodUnitException extends ParameterValuePolicyErrorException {
    public BadPeriodUnitException() {
      super("Periods for domain registrations must be specified in years");
    }
  }

  /** Missing type attribute for contact. */
  static class MissingContactTypeException extends ParameterValuePolicyErrorException {
    public MissingContactTypeException() {
      super("Missing type attribute for contact");
    }
  }

  /** More than one contact for a given role is not allowed. */
  static class DuplicateContactForRoleException extends ParameterValuePolicyErrorException {

    public DuplicateContactForRoleException(Multimap<Type, String> dupeContactsByType) {
      super(
          String.format(
              "More than one contact for a given role is not allowed: %s",
              dupeContactsByType.asMap().entrySet().stream()
                  .sorted(comparing(e -> e.getKey().name()))
                  .map(
                      e ->
                          String.format(
                              "role [%s] has contacts [%s]",
                              Ascii.toLowerCase(e.getKey().name()),
                              e.getValue().stream().sorted().collect(joining(", "))))
                  .collect(joining(", "))));
    }
  }

  /** Declared launch extension phase does not match the current registry phase. */
  static class LaunchPhaseMismatchException extends ParameterValuePolicyErrorException {
    public LaunchPhaseMismatchException() {
      super("Declared launch extension phase does not match the current registry phase");
    }
  }

  /** Too many DS records set on a domain. */
  static class TooManyDsRecordsException extends ParameterValuePolicyErrorException {
    public TooManyDsRecordsException(String message) {
      super(message);
    }
  }

  /** Domain name is under tld which doesn't exist. */
  static class TldDoesNotExistException extends ParameterValueRangeErrorException {
    public TldDoesNotExistException(String tld) {
      super(String.format("Domain name is under tld %s which doesn't exist", tld));
    }
  }

  /** Domain label is not allowed by IDN table. */
  public static class InvalidIdnDomainLabelException extends ParameterValueRangeErrorException {
    public InvalidIdnDomainLabelException() {
      super("Domain label is not allowed by IDN table");
    }
  }

  /** Registrant is required. */
  static class MissingRegistrantException extends RequiredParameterMissingException {
    public MissingRegistrantException() {
      super("Registrant is required");
    }
  }

  /** Admin contact is required. */
  static class MissingAdminContactException extends RequiredParameterMissingException {
    public MissingAdminContactException() {
      super("Admin contact is required");
    }
  }

  /** Technical contact is required. */
  static class MissingTechnicalContactException extends RequiredParameterMissingException {
    public MissingTechnicalContactException() {
      super("Technical contact is required");
    }
  }

  /** Too many nameservers set on this domain. */
  static class TooManyNameserversException extends ParameterValuePolicyErrorException {
    public TooManyNameserversException(String message) {
      super(message);
    }
  }

  /** Domain name must have exactly one part above the TLD. */
  static class BadDomainNamePartsCountException extends ParameterValueSyntaxErrorException {
    public BadDomainNamePartsCountException() {
      super("Domain name must have exactly one part above the TLD");
    }
  }

  /** Domain name must not equal an existing multi-part TLD. */
  static class DomainNameExistsAsTldException extends ParameterValueSyntaxErrorException {
    public DomainNameExistsAsTldException() {
      super("Domain name must not equal an existing multi-part TLD");
    }
  }

  /** Unknown fee command name. */
  static class UnknownFeeCommandException extends ParameterValuePolicyErrorException {
    UnknownFeeCommandException(String commandName) {
      super("Unknown fee command: " + commandName);
    }
  }

  /** Fee checks for command phases and subphases are not supported. */
  static class FeeChecksDontSupportPhasesException extends ParameterValuePolicyErrorException {
    FeeChecksDontSupportPhasesException() {
      super("Fee checks for command phases and subphases are not supported");
    }
  }

  /** The requested fees cannot be provided in the requested currency. */
  static class CurrencyUnitMismatchException extends ParameterValuePolicyErrorException {
    CurrencyUnitMismatchException() {
      super("The requested fees cannot be provided in the requested currency");
    }
  }

  /** The requested fee is expressed in a scale that is invalid for the given currency. */
  static class CurrencyValueScaleException extends ParameterValueSyntaxErrorException {
    CurrencyValueScaleException() {
      super("The requested fee is expressed in a scale that is invalid for the given currency");
    }
  }

  /** Fees must be explicitly acknowledged when performing any operations on a premium name. */
  static class FeesRequiredForPremiumNameException extends RequiredParameterMissingException {
    FeesRequiredForPremiumNameException() {
      super(
          "Fees must be explicitly acknowledged when performing any operations on a premium"
              + " name");
    }
  }

  /** Fees must be explicitly acknowledged when performing an operation which is not free. */
  static class FeesRequiredForNonFreeOperationException extends RequiredParameterMissingException {

    public FeesRequiredForNonFreeOperationException(Money expectedFee) {
      super(
          "Fees must be explicitly acknowledged when performing an operation which is not free."
              + " The total fee is: "
              + expectedFee);
    }
  }

  /** Fees must be explicitly acknowledged when creating domains during the Early Access Program. */
  static class FeesRequiredDuringEarlyAccessProgramException
      extends RequiredParameterMissingException {

    public FeesRequiredDuringEarlyAccessProgramException(Money expectedFee) {
      super(
          "Fees must be explicitly acknowledged when creating domains "
              + "during the Early Access Program. The EAP fee is: "
              + expectedFee);
    }
  }

  /** The 'grace-period', 'applied' and 'refundable' fields are disallowed by server policy. */
  static class UnsupportedFeeAttributeException extends UnimplementedOptionException {
    UnsupportedFeeAttributeException() {
      super(
          "The 'grace-period', 'refundable' and 'applied' attributes are disallowed by server "
              + "policy");
    }
  }

  /** Restores always renew a domain for one year. */
  static class RestoresAreAlwaysForOneYearException extends ParameterValuePolicyErrorException {
    RestoresAreAlwaysForOneYearException() {
      super("Restores always renew a domain for one year");
    }
  }

  /** Transfers always renew a domain for one year. */
  static class TransfersAreAlwaysForOneYearException extends ParameterValuePolicyErrorException {
    TransfersAreAlwaysForOneYearException() {
      super("Transfers always renew a domain for one year");
    }
  }

  /** Requested domain is reserved. */
  static class DomainReservedException extends StatusProhibitsOperationException {
    public DomainReservedException(String domainName) {
      super(String.format("%s is a reserved domain", domainName));
    }
  }

  /**
   * The requested domain name is on the premium price list, and this registrar has blocked premium
   * registrations.
   */
  static class PremiumNameBlockedException extends StatusProhibitsOperationException {
    public PremiumNameBlockedException() {
      super(
          "The requested domain name is on the premium price list, "
              + "and this registrar has blocked premium registrations");
    }
  }

  /** The fees passed in the transform command do not match the fees that will be charged. */
  public static class FeesMismatchException extends ParameterValueRangeErrorException {
    public FeesMismatchException() {
      super("The fees passed in the transform command do not match the fees that will be charged");
    }

    public FeesMismatchException(Money correctFee) {
      super(
          String.format(
              "The fees passed in the transform command do not match the expected total of %s",
              correctFee));
    }

    public FeesMismatchException(FeeType type) {
      super(
          String.format(
              "The fees passed in the transform command do not contain expected fee type \"%s\"",
              type));
    }

    public FeesMismatchException(FeeType type, Money correctFee) {
      super(
          String.format(
              "The fees passed in the transform command for type \"%s\" do not match the expected "
                  + "fee of %s",
              type, correctFee));
    }
  }

  /** The fee description passed in the transform command cannot be parsed. */
  public static class FeeDescriptionParseException extends ParameterValuePolicyErrorException {
    public FeeDescriptionParseException(String description) {
      super(
          (Strings.isNullOrEmpty(description)
                  ? "No fee description is present in the command, "
                  : "The fee description \""
                      + description
                      + "\" passed in the command cannot be parsed, ")
              + "please perform a domain check to obtain expected fee descriptions.");
    }
  }

  /** The fee description passed in the transform command matches multiple fee types. */
  public static class FeeDescriptionMultipleMatchesException
      extends ParameterValuePolicyErrorException {
    public FeeDescriptionMultipleMatchesException(
        String description, ImmutableList<FeeType> types) {
      super(
          String.format(
              "The fee description \"%s\" passed in the transform matches multiple fee types: %s",
              description,
              types.stream().map(FeeType::toString).collect(joining(", "))));
    }
  }

  /** Registrar is not authorized to access this TLD. */
  public static class NotAuthorizedForTldException extends AuthorizationErrorException {
    public NotAuthorizedForTldException(String tld) {
      super("Registrar is not authorized to access the TLD " + tld);
    }
  }

  /** Registrant is not allow-listed for this TLD. */
  public static class RegistrantNotAllowedException extends StatusProhibitsOperationException {
    public RegistrantNotAllowedException(String contactId) {
      super(String.format("Registrant with id %s is not allow-listed for this TLD", contactId));
    }
  }

  /** Nameservers are not allow-listed for this TLD. */
  public static class NameserversNotAllowedForTldException
      extends StatusProhibitsOperationException {
    public NameserversNotAllowedForTldException(Set<String> fullyQualifiedHostNames) {
      super(
          String.format(
              "Nameservers '%s' are not allow-listed for this TLD",
              Joiner.on(',').join(fullyQualifiedHostNames)));
    }
  }

  /** Nameservers not specified for domain on TLD with nameserver allow list. */
  public static class NameserversNotSpecifiedForTldWithNameserverAllowListException
      extends StatusProhibitsOperationException {
    public NameserversNotSpecifiedForTldWithNameserverAllowListException(String domain) {
      super(
          String.format(
              "At least one nameserver must be specified for domain %s"
                  + " on a TLD with nameserver allow list",
              domain));
    }
  }

  /** Command is not allowed in the current registry phase. */
  public static class BadCommandForRegistryPhaseException extends CommandUseErrorException {
    public BadCommandForRegistryPhaseException() {
      super("Command is not allowed in the current registry phase");
    }
  }

  /** The secDNS:all element must have value 'true' if present. */
  static class SecDnsAllUsageException extends ParameterValuePolicyErrorException {
    public SecDnsAllUsageException() {
      super("The secDNS:all element must have value 'true' if present");
    }
  }

  /** At least one of 'add' or 'rem' is required on a secDNS update. */
  static class EmptySecDnsUpdateException extends RequiredParameterMissingException {
    public EmptySecDnsUpdateException() {
      super("At least one of 'add' or 'rem' is required on a secDNS update");
    }
  }

  /** At least one dsData is required when using the secDNS extension. */
  static class DsDataRequiredException extends ParameterValuePolicyErrorException {
    public DsDataRequiredException() {
      super("At least one dsData is required when using the secDNS extension");
    }
  }

  /** The 'urgent' attribute is not supported. */
  static class UrgentAttributeNotSupportedException extends UnimplementedOptionException {
    public UrgentAttributeNotSupportedException() {
      super("The 'urgent' attribute is not supported");
    }
  }

  /** The 'maxSigLife' setting is not supported. */
  static class MaxSigLifeNotSupportedException extends UnimplementedOptionException {
    public MaxSigLifeNotSupportedException() {
      super("The 'maxSigLife' setting is not supported");
    }
  }

  /** Changing 'maxSigLife' is not supported. */
  static class MaxSigLifeChangeNotSupportedException extends UnimplementedOptionException {
    public MaxSigLifeChangeNotSupportedException() {
      super("Changing 'maxSigLife' is not supported");
    }
  }

  /** The specified trademark validator is not supported. */
  static class InvalidTrademarkValidatorException extends ParameterValuePolicyErrorException {
    public InvalidTrademarkValidatorException() {
      super("The only supported validationID is 'tmch' for the ICANN Trademark Clearinghouse.");
    }
  }

  /** The expiration time specified in the claim notice has elapsed. */
  static class ExpiredClaimException extends ParameterValueRangeErrorException {
    public ExpiredClaimException() {
      super("The expiration time specified in the claim notice has elapsed");
    }
  }

  /** The acceptance time specified in the claim notice is more than 48 hours in the past. */
  static class AcceptedTooLongAgoException extends ParameterValueRangeErrorException {
    public AcceptedTooLongAgoException() {
      super("The acceptance time specified in the claim notice is more than 48 hours in the past");
    }
  }

  /** The specified TCNID is invalid. */
  static class MalformedTcnIdException extends ParameterValueSyntaxErrorException {
    public MalformedTcnIdException() {
      super("The specified TCNID is malformed");
    }
  }

  /** The checksum in the specified TCNID does not validate. */
  static class InvalidTcnIdChecksumException extends ParameterValueRangeErrorException {
    public InvalidTcnIdChecksumException() {
      super("The checksum in the specified TCNID does not validate");
    }
  }

  /** The claims period for this TLD has ended. */
  static class ClaimsPeriodEndedException extends StatusProhibitsOperationException {
    public ClaimsPeriodEndedException(String tld) {
      super(String.format("The claims period for %s has ended", tld));
    }
  }

  /** Requested domain requires a claims notice. */
  static class MissingClaimsNoticeException extends StatusProhibitsOperationException {
    public MissingClaimsNoticeException(String domainName) {
      super(String.format("%s requires a claims notice", domainName));
    }
  }

  /** Requested domain does not require a claims notice. */
  static class UnexpectedClaimsNoticeException extends StatusProhibitsOperationException {
    public UnexpectedClaimsNoticeException(String domainName) {
      super(String.format("%s does not require a claims notice", domainName));
    }
  }

  /** Only encoded signed marks are supported. */
  static class UnsupportedMarkTypeException extends ParameterValuePolicyErrorException {
    public UnsupportedMarkTypeException() {
      super("Only encoded signed marks are supported");
    }
  }

  /** New registration period exceeds maximum number of years. */
  static class ExceedsMaxRegistrationYearsException extends ParameterValueRangeErrorException {
    public ExceedsMaxRegistrationYearsException() {
      super(
          String.format(
              "New registration period exceeds maximum number of years (%d)",
              MAX_REGISTRATION_YEARS));
    }
  }

  /** Registrar must be active in order to perform this operation. */
  static class RegistrarMustBeActiveForThisOperationException extends AuthorizationErrorException {
    public RegistrarMustBeActiveForThisOperationException() {
      super("Registrar must be active in order to perform this operation");
    }
  }
}
