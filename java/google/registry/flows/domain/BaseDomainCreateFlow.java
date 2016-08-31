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

import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.validateContactsHaveTypes;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.validateDsData;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversCountForTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNoDuplicateContacts;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrantAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateRequiredContactsPresent;
import static google.registry.flows.domain.DomainFlowUtils.verifyLaunchPhase;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPendingDelete;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifySignedMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRoid;
import static google.registry.model.EppResourceUtils.loadByUniqueId;
import static google.registry.model.domain.fee.Fee.FEE_CREATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.model.registry.label.ReservedList.matchesAnchorTenantReservation;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.base.Optional;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.ResourceCreateFlow;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainBase.Builder;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.LrpToken;
import google.registry.model.domain.fee.FeeTransformCommandExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.launch.LaunchNotice.InvalidChecksumException;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.smd.SignedMark;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.pricing.TldSpecificLogicProxy;
import google.registry.pricing.TldSpecificLogicProxy.EppCommandOperations;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An EPP flow that creates a new domain resource or application.
 *
 * @param <R> the resource type being created
 * @param <B> a builder for the resource
 */
public abstract class BaseDomainCreateFlow<R extends DomainBase, B extends Builder<R, ?>>
    extends ResourceCreateFlow<R, B, Create> {

  private SecDnsCreateExtension secDnsCreate;

  protected LaunchCreateExtension launchCreate;
  protected String domainLabel;
  protected InternetDomainName domainName;
  protected String idnTableName;
  protected FeeTransformCommandExtension feeCreate;
  protected EppCommandOperations commandOperations;
  protected boolean hasSignedMarks;
  protected SignedMark signedMark;
  protected boolean isAnchorTenantViaReservation;
  protected TldState tldState;
  protected Optional<LrpToken> lrpToken;

  @Override
  public final void initResourceCreateOrMutateFlow() throws EppException {
    command = cloneAndLinkReferences(command, now);
    registerExtensions(SecDnsCreateExtension.class);
    secDnsCreate = eppInput.getSingleExtension(SecDnsCreateExtension.class);
    launchCreate = eppInput.getSingleExtension(LaunchCreateExtension.class);
    feeCreate =
        eppInput.getFirstExtensionOfClasses(FEE_CREATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER);
    hasSignedMarks = launchCreate != null && !launchCreate.getSignedMarks().isEmpty();
    initDomainCreateFlow();
  }

  @Override
  @Nullable
  protected String createFlowRepoId() {
    // The domain name hasn't been validated yet, so if it's invalid, instead of throwing an error,
    // simply leave the repoId blank (it won't be needed anyway as the flow will fail when
    // validation fails later).
    try {
      Optional<InternetDomainName> tldParsed =
          findTldForName(InternetDomainName.from(command.getFullyQualifiedDomainName()));
      return tldParsed.isPresent()
          ? createDomainRoid(ObjectifyService.allocateId(), tldParsed.get().toString())
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Subclasses may override this to do more specific initialization. */
  protected void initDomainCreateFlow() {}

  /**
   * Returns the tld of the domain being created.
   *
   * <p>Update/delete domain-related flows can simply grab the tld using existingResource.getTld(),
   * but in the create flows, the resource doesn't exist yet.  So we grab it off the domain name
   * that the flow is attempting to create.
   *
   * <p>Note that it's not always safe to call this until after the domain name has been validated
   * in verifyCreateIsAllowed().
   */
  protected String getTld() {
    return domainName.parent().toString();
  }

  /**
   * Fail the domain or application create very fast if the domain is already registered.
   *
   * <p>Try to load the domain non-transactionally, since this can hit memcache. If we succeed, and
   * the domain is not in the ADD grace period (the only state that allows instantaneous transition
   * to being deleted), we can assume that the domain will not be deleted (and therefore won't be
   * creatable) until its deletion time. For repeated failed creates this means we can avoid the
   * datastore lookup, which is very expensive (and first-seen failed creates are no worse than they
   * otherwise would be). This comes at the cost of the extra lookup for successful creates (or
   * rather, those that don't fail due to the domain existing) and also for failed creates within
   * the existing domain's ADD grace period.
   */
  @Override
  protected final void failfast() throws EppException {
    // Enter a transactionless context briefly.
    DomainResource domain = ofy().doTransactionless(new Work<DomainResource>() {
      @Override
      public DomainResource run() {
        // This is cacheable because we are outside of a transaction.
        return loadByUniqueId(DomainResource.class, targetId, now);
      }});
    // If the domain exists already and isn't in the ADD grace period then there is no way it will
    // be suddenly deleted and therefore the create must fail.
    if (domain != null
        && !domain.getGracePeriodStatuses().contains(GracePeriodStatus.ADD)) {
      throw new ResourceAlreadyExistsException(targetId, true);
    }
  }

  /** Fail if the create command is somehow invalid. */
  @Override
  protected final void verifyCreateIsAllowed() throws EppException {
    // Validate that this is actually a legal domain name on a TLD that the registrar has access to.
    domainName = validateDomainName(command.getFullyQualifiedDomainName());
    idnTableName = validateDomainNameWithIdnTables(domainName);
    String tld = getTld();
    checkAllowedAccessToTld(getAllowedTlds(), tld);
    Registry registry = Registry.get(tld);
    tldState = registry.getTldState(now);
    checkRegistryStateForTld(tld);
    domainLabel = domainName.parts().get(0);
    commandOperations = TldSpecificLogicProxy.getCreatePrice(
        registry, domainName.toString(), now, command.getPeriod().getValue());
    // The TLD should always be the parent of the requested domain name.
    isAnchorTenantViaReservation = matchesAnchorTenantReservation(
        domainLabel, tld, command.getAuthInfo().getPw().getValue());
    boolean isLrpApplication =
        registry.getLrpTldStates().contains(tldState)
            && !command.getAuthInfo().getPw().getValue().isEmpty()
            && !isAnchorTenantViaReservation;
    lrpToken = isLrpApplication
        ? TldSpecificLogicProxy.getMatchingLrpToken(command, tld)
        : Optional.<LrpToken>absent();
    // Superusers can create reserved domains, force creations on domains that require a claims
    // notice without specifying a claims key, and override blocks on registering premium domains.
    if (!isSuperuser) {
      boolean isSunriseApplication =
          launchCreate != null && !launchCreate.getSignedMarks().isEmpty();
      if (!isAnchorTenantViaReservation) {
        verifyNotReserved(domainName, isSunriseApplication);
      }
      if (isLrpApplication && !lrpToken.isPresent()) {
          throw new BadAuthInfoForResourceException();
      }
      boolean isClaimsPeriod = now.isBefore(registry.getClaimsPeriodEnd());
      boolean isClaimsCreate = launchCreate != null && launchCreate.getNotice() != null;
      if (isClaimsPeriod) {
        boolean labelOnClaimsList = ClaimsListShard.get().getClaimKey(domainLabel) != null;
        if (labelOnClaimsList && !isSunriseApplication && !isClaimsCreate) {
          throw new MissingClaimsNoticeException(domainName.toString());
        }
        if (!labelOnClaimsList && isClaimsCreate) {
          throw new UnexpectedClaimsNoticeException(domainName.toString());
        }
      } else if (isClaimsCreate) {
        throw new ClaimsPeriodEndedException(tld);
      }
      verifyPremiumNameIsNotBlocked(targetId, now, getClientId());
    }
    verifyUnitIsYears(command.getPeriod());
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
    validateLaunchCreateExtension();
    // If a signed mark was provided, then it must match the desired domain label.
    // We do this after validating the launch create extension so that flows which don't allow any
    // signed marks throw a more useful error message rather than complaining about specific issues
    // with the signed marks.
    if (hasSignedMarks) {
      signedMark = verifySignedMarks(launchCreate.getSignedMarks(), domainLabel, now);
    }
    validateSecDnsExtension();
    verifyDomainCreateIsAllowed();
  }

  @Override
  protected void modifyCreateRelatedResources() {
    if (lrpToken.isPresent()) {
      ofy().save().entity(lrpToken.get().asBuilder()
          .setRedemptionHistoryEntry(Key.create(historyEntry))
          .build());
    }
  }

  /** Validate the secDNS extension, if present. */
  private void validateSecDnsExtension() throws EppException {
    if (secDnsCreate != null) {
      if (secDnsCreate.getDsData() == null) {
        throw new DsDataRequiredException();
      }
      if (secDnsCreate.getMaxSigLife() != null) {
        throw new MaxSigLifeNotSupportedException();
      }
      validateDsData(secDnsCreate.getDsData());
    }
  }

  /**
   * If a launch create extension was given (always present for application creates, optional for
   * domain creates) then validate it.
   */
  private void validateLaunchCreateExtension() throws EppException {
    if (launchCreate == null) {
      return;
    }
    if (!isSuperuser) {  // Superusers can ignore the phase.
      verifyLaunchPhase(getTld(), launchCreate, now);
    }
    if (launchCreate.hasCodeMarks()) {
      throw new UnsupportedMarkTypeException();
    }
    validateDomainLaunchCreateExtension();
    LaunchNotice notice = launchCreate.getNotice();
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

  /** Subclasses may override this to do more specific checks. */
  @SuppressWarnings("unused")
  protected void verifyDomainCreateIsAllowed() throws EppException {}

  /** Subclasses may override this to do more specific validation of the launchCreate extension. */
  @SuppressWarnings("unused")
  protected void validateDomainLaunchCreateExtension() throws EppException {}

  /** Handle the secDNS extension */
  @Override
  protected final void setCreateProperties(B builder) throws EppException {
    if (secDnsCreate != null) {
      builder.setDsData(secDnsCreate.getDsData());
    }
    builder.setLaunchNotice(launchCreate == null ? null : launchCreate.getNotice());
    setDomainCreateProperties(builder);
    builder.setIdnTableName(idnTableName);
  }

  protected abstract void setDomainCreateProperties(B builder) throws EppException;

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

  /** The claims period for this TLD has ended. */
  static class ClaimsPeriodEndedException extends StatusProhibitsOperationException {
    public ClaimsPeriodEndedException(String tld) {
      super(String.format("The claims period for %s has ended", tld));
    }
  }

  /** The specified trademark validator is not supported. */
  static class InvalidTrademarkValidatorException extends ParameterValuePolicyErrorException {
    public InvalidTrademarkValidatorException() {
      super("The only supported validationID is 'tmch' for the ICANN Trademark Clearinghouse.");
    }
  }

  /** At least one dsData is required when using the secDNS extension. */
  static class DsDataRequiredException extends ParameterValuePolicyErrorException {
    public DsDataRequiredException() {
      super("At least one dsData is required when using the secDNS extension");
    }
  }

  /** Only encoded signed marks are supported. */
  static class UnsupportedMarkTypeException extends ParameterValuePolicyErrorException {
    public UnsupportedMarkTypeException() {
      super("Only encoded signed marks are supported");
    }
  }

  /** The 'maxSigLife' setting is not supported. */
  static class MaxSigLifeNotSupportedException extends UnimplementedOptionException {
    public MaxSigLifeNotSupportedException() {
      super("The 'maxSigLife' setting is not supported");
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
}
