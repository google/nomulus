// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.flows.EppXmlTransformer.unmarshal;
import static google.registry.flows.domain.TldSpecificLogicProxy.getMatchingLrpToken;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.model.registry.label.ReservedList.getReservation;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.tldconfig.idn.IdnLabelValidator.findValidIdnTableForTld;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DomainNameUtils.ACE_PREFIX;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.InvalidAuthorizationInformationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.domain.DomainPricingLogic.FeesAndCredits;
import google.registry.flows.exceptions.ResourceAlreadyExistsException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.model.EppResource;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainCommand.CreateOrUpdate;
import google.registry.model.domain.DomainCommand.InvalidReferencesException;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.LrpTokenEntity;
import google.registry.model.domain.Period;
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
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsInfoExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Add;
import google.registry.model.domain.secdns.SecDnsUpdateExtension.Remove;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.host.HostResource;
import google.registry.model.mark.Mark;
import google.registry.model.mark.ProtectedMark;
import google.registry.model.mark.Trademark;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservationType;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.AbstractSignedMark;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.model.smd.SignedMark;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.tmch.TmchXmlSignature;
import google.registry.tmch.TmchXmlSignature.CertificateSignatureException;
import google.registry.util.Idn;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.parsers.ParserConfigurationException;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.xml.sax.SAXException;

/** Static utility functions for domain flows. */
public class DomainFlowUtils {

  /** Map from launch phases to the equivalent tld states. */
  private static final ImmutableMap<LaunchPhase, TldState> LAUNCH_PHASE_TO_TLD_STATE =
      ImmutableMap.of(
          LaunchPhase.SUNRISE, TldState.SUNRISE,
          LaunchPhase.SUNRUSH, TldState.SUNRUSH,
          LaunchPhase.LANDRUSH, TldState.LANDRUSH,
          LaunchPhase.CLAIMS, TldState.GENERAL_AVAILABILITY,
          LaunchPhase.OPEN, TldState.GENERAL_AVAILABILITY);

  /** Reservation types that are allowed in sunrise by policy. */
  public static final ImmutableSet<ReservationType> TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE =
      Sets.immutableEnumSet(
          ReservationType.ALLOWED_IN_SUNRISE,
          ReservationType.NAME_COLLISION,
          ReservationType.MISTAKEN_PREMIUM);

  /** Non-sunrise tld states. */
  private static final ImmutableSet<TldState> DISALLOWED_TLD_STATES_FOR_LAUNCH_FLOWS =
      Sets.immutableEnumSet(
          TldState.PREDELEGATION,
          TldState.QUIET_PERIOD,
          TldState.GENERAL_AVAILABILITY);

  /** Strict validator for ascii lowercase letters, digits, and "-", allowing "." as a separator */
  private static final CharMatcher ALLOWED_CHARS =
      CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('0', '9').or(CharMatcher.anyOf("-.")));

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
  static InternetDomainName validateDomainName(String name)
      throws EppException {
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
  static String validateDomainNameWithIdnTables(InternetDomainName domainName)
      throws InvalidIdnDomainLabelException {
    Optional<String> idnTableName =
        findValidIdnTableForTld(domainName.parts().get(0), domainName.parent().toString());
    if (!idnTableName.isPresent()) {
      throw new InvalidIdnDomainLabelException();
    }
    return idnTableName.get();
  }

  /** Check if the registrar running the flow has access to the TLD in question. */
  public static void checkAllowedAccessToTld(String clientId, String tld)
      throws EppException {
    if (!Registrar.loadByClientId(clientId).getAllowedTlds().contains(tld)) {
      throw new DomainFlowUtils.NotAuthorizedForTldException(tld);
    }
  }

  /** Check that the DS data that will be set on a domain is valid. */
  static void validateDsData(Set<DelegationSignerData> dsData) throws EppException {
    if (dsData != null && dsData.size() > MAX_DS_RECORDS_PER_DOMAIN) {
      throw new TooManyDsRecordsException(String.format(
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
      Key<ContactResource> registrant,
      Set<Key<HostResource>> nameservers) throws EppException {
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      verifyNotInPendingDelete(contact.getContactKey());
    }
    if (registrant != null) {
      verifyNotInPendingDelete(registrant);
    }
    for (Key<HostResource> host : nullToEmpty(nameservers)) {
      verifyNotInPendingDelete(host);
    }
  }

  private static void verifyNotInPendingDelete(
      Key<? extends EppResource> resourceKey) throws EppException {

    EppResource resource = ofy().load().key(resourceKey).now();
    if (resource.getStatusValues().contains(StatusValue.PENDING_DELETE)) {
      throw new LinkedResourceInPendingDeleteProhibitsOperationException(resource.getForeignKey());
    }
  }

  static void validateContactsHaveTypes(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      if (contact.getType() == null) {
        throw new MissingContactTypeException();
      }
    }
  }

  static void validateNameserversCountForTld(String tld, int count) throws EppException {
    ImmutableSet<String> whitelist = Registry.get(tld).getAllowedFullyQualifiedHostNames();
    // For TLDs with a nameserver whitelist, all domains must have at least 1 nameserver.
    if (!whitelist.isEmpty() && count == 0) {
      throw new NameserversNotSpecifiedException();
    }
    if (count > MAX_NAMESERVERS_PER_DOMAIN) {
      throw new TooManyNameserversException(String.format(
          "Only %d nameservers are allowed per domain", MAX_NAMESERVERS_PER_DOMAIN));
    }
  }

  static void validateNoDuplicateContacts(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    Set<Type> roles = new HashSet<>();
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      if (!roles.add(contact.getType())) {
        throw new DuplicateContactForRoleException();
      }
    }
  }

  static void validateRequiredContactsPresent(
      Key<ContactResource> registrant, Set<DesignatedContact> contacts)
          throws RequiredParameterMissingException {
    if (registrant == null) {
      throw new MissingRegistrantException();
    }

    Set<Type> roles = new HashSet<>();
    for (DesignatedContact contact : nullToEmpty(contacts)) {
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
    ImmutableSet<String> whitelist = Registry.get(tld).getAllowedRegistrantContactIds();
    // Empty whitelist or null registrantContactId are ignored.
    if (registrantContactId != null && !whitelist.isEmpty()
        && !whitelist.contains(registrantContactId)) {
      throw new RegistrantNotAllowedException(registrantContactId);
    }
  }

  static void validateNameserversAllowedOnTld(String tld, Set<String> fullyQualifiedHostNames)
      throws EppException {
    ImmutableSet<String> whitelist = Registry.get(tld).getAllowedFullyQualifiedHostNames();
    Set<String> hostnames = nullToEmpty(fullyQualifiedHostNames);
    if (!whitelist.isEmpty()) { // Empty whitelist is ignored.
      Set<String> disallowedNameservers = difference(hostnames, whitelist);
      if (!disallowedNameservers.isEmpty()) {
        throw new NameserversNotAllowedException(disallowedNameservers);
      }
    }
  }

  static void verifyNotReserved(InternetDomainName domainName, boolean isSunrise)
      throws EppException {
    if (isReserved(domainName, isSunrise)) {
      throw new DomainReservedException(domainName.toString());
    }
  }

  private static boolean isReserved(InternetDomainName domainName, boolean isSunrise) {
    ReservationType type = getReservationType(domainName);
    return type == ReservationType.FULLY_BLOCKED
        || type == ReservationType.RESERVED_FOR_ANCHOR_TENANT
        || (TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE.contains(type) && !isSunrise);
  }

  /** Returns an enum that encodes how and when this name is reserved in the current tld. */
  static ReservationType getReservationType(InternetDomainName domainName) {
    // The TLD should always be the parent of the requested domain name.
    return getReservation(domainName.parts().get(0), domainName.parent().toString());
  }

  /** Verifies that a launch extension's specified phase matches the specified registry's phase. */
  static void verifyLaunchPhaseMatchesRegistryPhase(
      Registry registry, LaunchExtension launchExtension, DateTime now) throws EppException {
    if (!Objects.equals(
        registry.getTldState(now),
        LAUNCH_PHASE_TO_TLD_STATE.get(launchExtension.getPhase()))) {
      // No launch operations are allowed during the quiet period or predelegation.
      throw new LaunchPhaseMismatchException();
    }
  }

  /** Verifies that an application's domain name matches the target id (from a command). */
  static void verifyApplicationDomainMatchesTargetId(
      DomainApplication application, String targetId) throws EppException {
    if (!application.getFullyQualifiedDomainName().equals(targetId)) {
      throw new ApplicationDomainNameMismatchException();
    }
  }

  /**
   * Verifies that a domain name is allowed to be delegated to the given client id. The only case
   * where it would not be allowed is if domain name is premium, and premium names are blocked by
   * this registrar.
   */
  static void verifyPremiumNameIsNotBlocked(
      String domainName, DateTime priceTime, String clientId) throws EppException {
    if (isDomainPremium(domainName, priceTime)) {
      // NB: The load of the Registar object is transactionless, which means that it should hit
      // memcache most of the time.
      if (Registrar.loadByClientId(clientId).getBlockPremiumNames()) {
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
  static BillingEvent.Recurring.Builder newAutorenewBillingEvent(DomainResource domain) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime());
  }

  /**
   * Fills in a builder with the data needed for an autorenew poll message for this domain. This
   * does not copy over the id of the current autorenew poll message.
   */
  static PollMessage.Autorenew.Builder newAutorenewPollMessage(DomainResource domain) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime())
        .setMsg("Domain was auto-renewed.");
  }

  /**
   * Re-saves the current autorenew billing event and poll message with a new end time. This may end
   * up deleting the poll message (if closing the message interval) or recreating it (if opening the
   * message interval).
   */
  @SuppressWarnings("unchecked")
  static void updateAutorenewRecurrenceEndTime(DomainResource domain, DateTime newEndTime) {
    Optional<PollMessage.Autorenew> autorenewPollMessage =
        Optional.fromNullable(ofy().load().key(domain.getAutorenewPollMessage()).now());

    // Construct an updated autorenew poll message. If the autorenew poll message no longer exists,
    // create a new one at the same id. This can happen if a transfer was requested on a domain
    // where all autorenew poll messages had already been delivered (this would cause the poll
    // message to be deleted), and then subsequently the transfer was canceled, rejected, or deleted
    // (which would cause the poll message to be recreated here).
    Key<PollMessage.Autorenew> existingAutorenewKey = domain.getAutorenewPollMessage();
    PollMessage.Autorenew updatedAutorenewPollMessage = autorenewPollMessage.isPresent()
        ? autorenewPollMessage.get().asBuilder().setAutorenewEndTime(newEndTime).build()
        : newAutorenewPollMessage(domain)
            .setId(existingAutorenewKey.getId())
            .setAutorenewEndTime(newEndTime)
            .setParentKey(existingAutorenewKey.<HistoryEntry>getParent())
            .build();

    // If the resultant autorenew poll message would have no poll messages to deliver, then just
    // delete it. Otherwise save it with the new end time.
    if (isAtOrAfter(updatedAutorenewPollMessage.getEventTime(), newEndTime)) {
      if (autorenewPollMessage.isPresent()) {
        ofy().delete().entity(autorenewPollMessage.get());
      }
    } else {
      ofy().save().entity(updatedAutorenewPollMessage);
    }

    ofy().save().entity(ofy().load().key(domain.getAutorenewBillingEvent()).now().asBuilder()
        .setRecurrenceEndTime(newEndTime)
        .build());
  }

  static SignedMark verifySignedMarks(
      ImmutableList<AbstractSignedMark> signedMarks, String domainLabel, DateTime now)
          throws EppException {
    if (signedMarks.size() > 1) {
      throw new TooManySignedMarksException();
    }
    if (!(signedMarks.get(0) instanceof EncodedSignedMark)) {
      throw new SignedMarksMustBeEncodedException();
    }
    return verifyEncodedSignedMark((EncodedSignedMark) signedMarks.get(0), domainLabel, now);
  }

  public static SignedMark verifyEncodedSignedMark(
      EncodedSignedMark encodedSignedMark, String domainLabel, DateTime now) throws EppException {
    if (!encodedSignedMark.getEncoding().equals("base64")) {
      throw new Base64RequiredForEncodedSignedMarksException();
    }
    byte[] signedMarkData;
    try {
      signedMarkData = encodedSignedMark.getBytes();
    } catch (IllegalStateException e) {
      throw new SignedMarkEncodingErrorException();
    }

    SignedMark signedMark;
    try {
      signedMark = unmarshal(SignedMark.class, signedMarkData);
    } catch (EppException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (SignedMarkRevocationList.get().isSmdRevoked(signedMark.getId(), now)) {
      throw new SignedMarkRevokedErrorException();
    }

    try {
      TmchXmlSignature.verify(signedMarkData);
    } catch (CertificateExpiredException e) {
      throw new SignedMarkCertificateExpiredException();
    } catch (CertificateNotYetValidException e) {
      throw new SignedMarkCertificateNotYetValidException();
    } catch (CertificateRevokedException e) {
      throw new SignedMarkCertificateRevokedException();
    } catch (CertificateSignatureException e) {
      throw new SignedMarkCertificateSignatureException();
    } catch (SignatureException | XMLSignatureException e) {
      throw new SignedMarkSignatureException();
    } catch (GeneralSecurityException e) {
      throw new SignedMarkCertificateInvalidException();
    } catch (IOException
        | MarshalException
        | SAXException
        | ParserConfigurationException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (!(isAtOrAfter(now, signedMark.getCreationTime())
          && now.isBefore(signedMark.getExpirationTime())
          && containsMatchingLabel(signedMark.getMark(), domainLabel))) {
      throw new NoMarksFoundMatchingDomainException();
    }
    return signedMark;
  }

  /** Returns true if the mark contains a valid claim that matches the label. */
  static boolean containsMatchingLabel(Mark mark, String label) {
    for (Trademark trademark : mark.getTrademarks()) {
      if (trademark.getLabels().contains(label)) {
        return true;
      }
    }
    for (ProtectedMark protectedMark
        : concat(mark.getTreatyOrStatuteMarks(), mark.getCourtMarks())) {
      if (protectedMark.getLabels().contains(label)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Validates a {@link FeeQueryCommandExtensionItem} and sets the appropriate fields on a {@link
   * FeeQueryResponseExtensionItem} builder.
   */
  static void handleFeeRequest(
      FeeQueryCommandExtensionItem feeRequest,
      FeeQueryResponseExtensionItem.Builder<?, ?> builder,
      InternetDomainName domain,
      String clientId,
      @Nullable CurrencyUnit topLevelCurrency,
      DateTime currentDate,
      EppInput eppInput,
      DomainPricingLogic pricingLogic)
      throws EppException {
    DateTime now = currentDate;
    // Use the custom effective date specified in the fee check request, if there is one.
    if (feeRequest.getEffectiveDate().isPresent()) {
      now = feeRequest.getEffectiveDate().get();
      builder.setEffectiveDateIfSupported(now);
    }
    String domainNameString = domain.toString();
    Registry registry = Registry.get(domain.parent().toString());
    int years = verifyUnitIsYears(feeRequest.getPeriod()).getValue();
    boolean isSunrise = registry.getTldState(now).equals(TldState.SUNRISE);

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
        .setPeriod(feeRequest.getPeriod())
        .setClass(TldSpecificLogicProxy.getFeeClass(domainNameString, now).orNull());

    ImmutableList<Fee> fees = ImmutableList.of();
    switch (feeRequest.getCommandName()) {
      case CREATE:
        if (isReserved(domain, isSunrise)) { // Don't return a create price for reserved names.
          builder.setClass("reserved"); // Override whatever class we've set above.
          builder.setAvailIfSupported(false);
          builder.setReasonIfSupported("reserved");
        } else {
          builder.setAvailIfSupported(true);
          fees = pricingLogic.getCreatePrice(registry, domainNameString, now, years).getFees();
        }
        break;
      case RENEW:
        builder.setAvailIfSupported(true);
        fees = TldSpecificLogicProxy.getRenewPrice(
            registry, domainNameString, clientId, now, years, eppInput).getFees();
        break;
      case RESTORE:
        if (years != 1) {
          throw new RestoresAreAlwaysForOneYearException();
        }
        builder.setAvailIfSupported(true);
        fees = TldSpecificLogicProxy.getRestorePrice(
            registry, domainNameString, clientId, now, eppInput).getFees();
        break;
      case TRANSFER:
        builder.setAvailIfSupported(true);
        fees = TldSpecificLogicProxy.getTransferPrice(
            registry, domainNameString, clientId, now, years, eppInput).getFees();
        break;
      case UPDATE:
        builder.setAvailIfSupported(true);
        fees = TldSpecificLogicProxy.getUpdatePrice(
            registry, domainNameString, clientId, now, eppInput).getFees();
        break;
      default:
        throw new UnknownFeeCommandException(feeRequest.getUnparsedCommandName());
    }

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

  public static void validateFeeChallenge(
      String domainName,
      String tld,
      DateTime priceTime,
      final FeeTransformCommandExtension feeCommand,
      Money cost,
      Money... otherCosts)
      throws EppException {
    Registry registry = Registry.get(tld);
    if (registry.getPremiumPriceAckRequired()
        && isDomainPremium(domainName, priceTime)
        && feeCommand == null) {
      throw new FeesRequiredForPremiumNameException();
    }
    if (feeCommand == null) {
      return;
    }
    List<Fee> fees = feeCommand.getFees();
    // The schema guarantees that at least one fee will be present.
    checkState(!fees.isEmpty());
    BigDecimal total = BigDecimal.ZERO;
    for (Fee fee : fees) {
      if (!fee.hasDefaultAttributes()) {
        throw new UnsupportedFeeAttributeException();
      }
      total = total.add(fee.getCost());
    }
    for (Credit credit : feeCommand.getCredits()) {
      if (!credit.hasDefaultAttributes()) {
        throw new UnsupportedFeeAttributeException();
      }
      total = total.add(credit.getCost());
    }

    Money feeTotal = null;
    try {
      feeTotal = Money.of(feeCommand.getCurrency(), total);
    } catch (ArithmeticException e) {
      throw new CurrencyValueScaleException();
    }

    Money costTotal = cost;
    for (Money otherCost : otherCosts) {
      costTotal = costTotal.plus(otherCost);
    }

    if (!feeTotal.getCurrencyUnit().equals(costTotal.getCurrencyUnit())) {
      throw new CurrencyUnitMismatchException();
    }
    if (!feeTotal.equals(costTotal)) {
      throw new FeesMismatchException(costTotal);
    }
  }

  /** Create a poll message for the gaining client in a transfer. */
  static PollMessage createGainingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      HistoryEntry historyEntry) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getGainingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(ImmutableList.of(
            createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime),
            DomainPendingActionNotificationResponse.create(
                  targetId,
                  transferData.getTransferStatus().isApproved(),
                  transferData.getTransferRequestTrid(),
                  historyEntry.getModificationTime())))
        .setParent(historyEntry)
        .build();
  }

  /** Create a poll message for the losing client in a transfer. */
  static PollMessage createLosingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      HistoryEntry historyEntry) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getLosingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(ImmutableList.of(
            createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime)))
        .setParent(historyEntry)
        .build();
  }

  /** Create a {@link DomainTransferResponse} off of the info in a {@link TransferData}. */
  static DomainTransferResponse createTransferResponse(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime) {
    return new DomainTransferResponse.Builder()
        .setFullyQualifiedDomainNameName(targetId)
        .setGainingClientId(transferData.getGainingClientId())
        .setLosingClientId(transferData.getLosingClientId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus())
        .setExtendedRegistrationExpirationTime(extendedRegistrationExpirationTime)
        .build();
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
    if (Boolean.TRUE.equals(secDnsUpdate.getUrgent())) {  // We allow both false and null.
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
      throw new SecDnsAllUsageException();  // Explicit all=false is meaningless.
    }
    Set<DelegationSignerData> toAdd = (add == null)
        ? ImmutableSet.<DelegationSignerData>of()
        : add.getDsData();
    Set<DelegationSignerData> toRemove = (remove == null)
        ? ImmutableSet.<DelegationSignerData>of()
        : (remove.getAll() == null) ? remove.getDsData() : oldDsData;
    // RFC 5910 specifies that removes are processed before adds.
    return ImmutableSet.copyOf(union(difference(oldDsData, toRemove), toAdd));
  }

  /** If a domain or application has "clientUpdateProhibited" set, updates must clear it or fail. */
  static void verifyClientUpdateNotProhibited(Update command, DomainBase existingResource)
      throws ResourceHasClientUpdateProhibitedException {
    if (existingResource.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)
        && !command.getInnerRemove().getStatusValues()
            .contains(StatusValue.CLIENT_UPDATE_PROHIBITED)) {
      throw new ResourceHasClientUpdateProhibitedException();
    }
  }

  /** Check that the registry phase is not incompatible with launch extension flows. */
  static void verifyRegistryStateAllowsLaunchFlows(Registry registry, DateTime now)
      throws BadCommandForRegistryPhaseException {
    if (DISALLOWED_TLD_STATES_FOR_LAUNCH_FLOWS.contains(registry.getTldState(now))) {
      throw new BadCommandForRegistryPhaseException();
    }
  }

  /** Check that the registry phase is not predelegation, during which some flows are forbidden. */
  static void verifyNotInPredelegation(Registry registry, DateTime now)
      throws BadCommandForRegistryPhaseException {
    if (registry.getTldState(now) == TldState.PREDELEGATION) {
      throw new BadCommandForRegistryPhaseException();
    }
  }

  /** Validate the contacts and nameservers specified in a domain or application create command. */
  static void validateCreateCommandContactsAndNameservers(Create command, String tld)
      throws EppException {
    verifyNotInPendingDelete(
        command.getContacts(),
        command.getRegistrant(),
        command.getNameservers());
    validateContactsHaveTypes(command.getContacts());
    validateRegistrantAllowedOnTld(tld, command.getRegistrantContactId());
    validateNoDuplicateContacts(command.getContacts());
    validateRequiredContactsPresent(command.getRegistrant(), command.getContacts());
    Set<String> fullyQualifiedHostNames =
        nullToEmpty(command.getNameserverFullyQualifiedHostNames());
    validateNameserversCountForTld(tld, fullyQualifiedHostNames.size());
    validateNameserversAllowedOnTld(tld, fullyQualifiedHostNames);
  }

  /**
   * Fail a domain or application create very fast if the domain is already registered.
   *
   * <p>Try to load the domain non-transactionally, since this can hit memcache. If we succeed, and
   * the domain is not in the add grace period (the only state that allows instantaneous transition
   * to being deleted), we can assume that the domain will not be deleted (and therefore won't be
   * creatable) until its deletion time. For repeated failed creates this means we can avoid the
   * Datastore lookup, which is very expensive (and first-seen failed creates are no worse than they
   * otherwise would be). This comes at the cost of the extra lookup for successful creates (or
   * rather, those that don't fail due to the domain existing) and also for failed creates within
   * the existing domain's add grace period.
   */
  static void failfastForCreate(final String targetId, final DateTime now) throws EppException {
    // Enter a transactionless context briefly.
    DomainResource domain = ofy().doTransactionless(new Work<DomainResource>() {
      @Override
      public DomainResource run() {
        // This is cacheable because we are outside of a transaction.
        return loadByForeignKey(DomainResource.class, targetId, now);
      }});
    // If the domain exists already and isn't in the add grace period then there is no way it will
    // be suddenly deleted and therefore the create must fail.
    if (domain != null && !domain.getGracePeriodStatuses().contains(GracePeriodStatus.ADD)) {
      throw new ResourceAlreadyExistsException(targetId, true);
    }
  }

  /** Validate the secDNS extension, if present. */
  static SecDnsCreateExtension validateSecDnsExtension(SecDnsCreateExtension secDnsCreate)
      throws EppException {
    if (secDnsCreate == null) {
      return null;
    }
    if (secDnsCreate.getDsData() == null) {
      throw new DsDataRequiredException();
    }
    if (secDnsCreate.getMaxSigLife() != null) {
      throw new MaxSigLifeNotSupportedException();
    }
    validateDsData(secDnsCreate.getDsData());
    return secDnsCreate;
  }

  /** Validate the notice from a launch create extension, allowing null as a valid notice. */
  static void validateLaunchCreateNotice(
      @Nullable LaunchNotice notice,
      String domainLabel,
      boolean isSuperuser,
      DateTime now) throws EppException {
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
   * Check that if there's a claims notice it's on the claims list, and that if there's not one it's
   * not on the claims list and is a sunrise application.
   */
  static void verifyClaimsNoticeIfAndOnlyIfNeeded(
      InternetDomainName domainName,
      boolean hasSignedMarks,
      boolean hasClaimsNotice) throws EppException {
    boolean isInClaimsList = ClaimsListShard.get().getClaimKey(domainName.parts().get(0)) != null;
    if (hasClaimsNotice && !isInClaimsList) {
      throw new UnexpectedClaimsNoticeException(domainName.toString());
    }
    if (!hasClaimsNotice && isInClaimsList && !hasSignedMarks) {
      throw new MissingClaimsNoticeException(domainName.toString());
    }
  }

  /** Create a {@link LrpTokenEntity} object that records this LRP registration. */
  static LrpTokenEntity prepareMarkedLrpTokenEntity(
      String lrpTokenString, InternetDomainName domainName, HistoryEntry historyEntry)
          throws InvalidLrpTokenException {
    Optional<LrpTokenEntity> lrpToken = getMatchingLrpToken(lrpTokenString, domainName);
    if (!lrpToken.isPresent()) {
      throw new InvalidLrpTokenException();
    }
    return lrpToken.get().asBuilder()
        .setRedemptionHistoryEntry(Key.create(historyEntry))
        .build();
  }

  /** Check that there are no code marks, which is a type of mark we don't support. */
  static void verifyNoCodeMarks(LaunchCreateExtension launchCreate)
      throws UnsupportedMarkTypeException {
    if (launchCreate.hasCodeMarks()) {
      throw new UnsupportedMarkTypeException();
    }
  }

  /** Create a response extension listign the fees on a domain or application create. */
  static FeeTransformResponseExtension createFeeCreateResponse(
      FeeTransformCommandExtension feeCreate, FeesAndCredits feesAndCredits) {
    return feeCreate
        .createResponseBuilder()
        .setCurrency(feesAndCredits.getCurrency())
        .setFees(feesAndCredits.getFees())
        .setCredits(feesAndCredits.getCredits())
        .build();
  }

  /** Encoded signed marks must use base64 encoding. */
  static class Base64RequiredForEncodedSignedMarksException
      extends ParameterValuePolicyErrorException {
    public Base64RequiredForEncodedSignedMarksException() {
      super("Encoded signed marks must use base64 encoding");
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
      super(String.format(
          "Linked resource in pending delete prohibits operation: %s",
          resourceId));
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
    public DuplicateContactForRoleException() {
      super("More than one contact for a given role is not allowed");
    }
  }

  /** Declared launch extension phase does not match the current registry phase. */
  static class LaunchPhaseMismatchException extends ParameterValuePolicyErrorException {
    public LaunchPhaseMismatchException() {
      super("Declared launch extension phase does not match the current registry phase");
    }
  }

  /** Application referenced does not match specified domain name. */
  static class ApplicationDomainNameMismatchException extends ParameterValuePolicyErrorException {
    public ApplicationDomainNameMismatchException() {
      super("Application referenced does not match specified domain name");
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
  static class InvalidIdnDomainLabelException extends ParameterValueRangeErrorException {
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

  /** Domain name must have exactly one part above the tld. */
  static class BadDomainNamePartsCountException extends ParameterValueSyntaxErrorException {
    public BadDomainNamePartsCountException() {
      super("Domain name must have exactly one part above the tld");
    }
  }

  /** Signed mark data is improperly encoded. */
  static class SignedMarkEncodingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkEncodingErrorException() {
      super("Signed mark data is improperly encoded");
    }
  }

  /** Error while parsing encoded signed mark data. */
  static class SignedMarkParsingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkParsingErrorException() {
      super("Error while parsing encoded signed mark data");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkSignatureException() {
      super("Signed mark signature is invalid");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkCertificateSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateSignatureException() {
      super("Signed mark certificate not signed by ICANN");
    }
  }

  /** Certificate used in signed mark signature was revoked by ICANN. */
  static class SignedMarkCertificateRevokedException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateRevokedException() {
      super("Signed mark certificate was revoked");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateExpiredException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateExpiredException() {
      super("Signed mark certificate has expired");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateNotYetValidException
      extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateNotYetValidException() {
      super("Signed mark certificate not yet valid");
    }
  }

  /** Certificate parsing error, or possibly a bad provider or algorithm. */
  static class SignedMarkCertificateInvalidException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateInvalidException() {
      super("Signed mark certificate is invalid");
    }
  }

  /** Signed mark data is revoked. */
  static class SignedMarkRevokedErrorException extends ParameterValuePolicyErrorException {
    public SignedMarkRevokedErrorException() {
      super("SMD has been revoked");
    }
  }

  /** Only one signed mark is allowed per application. */
  static class TooManySignedMarksException extends ParameterValuePolicyErrorException {
    public TooManySignedMarksException() {
      super("Only one signed mark is allowed per application");
    }
  }

  /** Signed marks must be encoded. */
  static class SignedMarksMustBeEncodedException extends ParameterValuePolicyErrorException {
    public SignedMarksMustBeEncodedException() {
      super("Signed marks must be encoded");
    }
  }

  /** The provided mark does not match the desired domain label. */
  static class NoMarksFoundMatchingDomainException extends RequiredParameterMissingException {
    public NoMarksFoundMatchingDomainException() {
      super("The provided mark does not match the desired domain label");
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
      super("Fees must be explicitly acknowledged when performing any operations on a premium"
          + " name");
    }
  }

  /** Fees must be explicitly acknowledged when performing an update which is not free. */
  static class FeesRequiredForNonFreeUpdateException extends RequiredParameterMissingException {
    FeesRequiredForNonFreeUpdateException() {
      super("Fees must be explicitly acknowledged when performing an update which is not free.");
    }
  }

  /** The 'grace-period', 'applied' and 'refundable' fields are disallowed by server policy. */
  static class UnsupportedFeeAttributeException extends UnimplementedOptionException {
    UnsupportedFeeAttributeException() {
      super("The 'grace-period', 'refundable' and 'applied' attributes are disallowed by server "
          + "policy");
    }
  }

  /** Restores always renew a domain for one year. */
  static class RestoresAreAlwaysForOneYearException extends ParameterValuePolicyErrorException {
    RestoresAreAlwaysForOneYearException() {
      super("Restores always renew a domain for one year");
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
      super("The requested domain name is on the premium price list, "
          + "and this registrar has blocked premium registrations");
    }
  }

  /** The fees passed in the transform command do not match the fees that will be charged. */
  public static class FeesMismatchException extends ParameterValueRangeErrorException {
    public FeesMismatchException() {
      super("The fees passed in the transform command do not match the fees that will be charged");
    }

    public FeesMismatchException(Money correctFee) {
      super(String.format(
          "The fees passed in the transform command do not match the expected total of %s",
          correctFee));
    }
  }

  /** Registrar is not authorized to access this TLD. */
  public static class NotAuthorizedForTldException extends AuthorizationErrorException {
    public NotAuthorizedForTldException(String tld) {
      super("Registrar is not authorized to access the TLD " + tld);
    }
  }

  /** Registrant is not whitelisted for this TLD. */
  public static class RegistrantNotAllowedException extends StatusProhibitsOperationException {
    public RegistrantNotAllowedException(String contactId) {
      super(String.format("Registrant with id %s is not whitelisted for this TLD", contactId));
    }
  }

  /** Nameservers are not whitelisted for this TLD. */
  public static class NameserversNotAllowedException extends StatusProhibitsOperationException {
    public NameserversNotAllowedException(Set<String> fullyQualifiedHostNames) {
      super(String.format(
          "Nameservers '%s' are not whitelisted for this TLD",
          Joiner.on(',').join(fullyQualifiedHostNames)));
    }
  }

  /** Nameservers not specified for this TLD with whitelist. */
  public static class NameserversNotSpecifiedException extends StatusProhibitsOperationException {
    public NameserversNotSpecifiedException() {
      super("At least one nameserver must be specified for this TLD");
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

  /** Invalid limited registration period token. */
  static class InvalidLrpTokenException
      extends InvalidAuthorizationInformationErrorException {
    public InvalidLrpTokenException() {
      super("Invalid limited registration period token");
    }
  }

  /** Only encoded signed marks are supported. */
  static class UnsupportedMarkTypeException extends ParameterValuePolicyErrorException {
    public UnsupportedMarkTypeException() {
      super("Only encoded signed marks are supported");
    }
  }
}
