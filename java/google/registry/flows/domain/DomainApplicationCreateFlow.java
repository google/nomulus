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

import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.createFeeCreateResponse;
import static google.registry.flows.domain.DomainFlowUtils.failfastForCreate;
import static google.registry.flows.domain.DomainFlowUtils.prepareMarkedLrpTokenEntity;
import static google.registry.flows.domain.DomainFlowUtils.validateCreateCommandContactsAndNameservers;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.validateLaunchCreateNotice;
import static google.registry.flows.domain.DomainFlowUtils.validateSecDnsExtension;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsNoticeIfAndOnlyIfNeeded;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsPeriodNotEnded;
import static google.registry.flows.domain.DomainFlowUtils.verifyLaunchPhaseMatchesRegistryPhase;
import static google.registry.flows.domain.DomainFlowUtils.verifyNoCodeMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistryStateAllowsLaunchFlows;
import static google.registry.flows.domain.DomainFlowUtils.verifySignedMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRoid;
import static google.registry.model.index.DomainApplicationIndex.loadActiveApplicationsByDomainName;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.ReservedList.matchesAnchorTenantReservation;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ObjectAlreadyExistsException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.custom.DomainApplicationCreateFlowCustomLogic;
import google.registry.flows.custom.DomainApplicationCreateFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainApplicationCreateFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainApplicationCreateFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainPricingLogic.FeesAndCredits;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeTransformCommandExtension;
import google.registry.model.domain.flags.FlagsCreateCommandExtension;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchCreateResponseExtension;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.index.DomainApplicationIndex;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.AbstractSignedMark;
import google.registry.model.smd.EncodedSignedMark;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that creates a new application for a domain resource.
 *
 * @error {@link google.registry.flows.exceptions.ResourceAlreadyExistsException}
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link DomainApplicationCreateFlow.LandrushApplicationDisallowedDuringSunriseException}
 * @error {@link DomainApplicationCreateFlow.NoticeCannotBeUsedWithSignedMarkException}
 * @error {@link DomainApplicationCreateFlow.SunriseApplicationDisallowedDuringLandrushException}
 * @error {@link DomainApplicationCreateFlow.UncontestedSunriseApplicationBlockedInLandrushException}
 * @error {@link DomainFlowUtils.AcceptedTooLongAgoException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.Base64RequiredForEncodedSignedMarksException}
 * @error {@link DomainFlowUtils.ClaimsPeriodEndedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.ExpiredClaimException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidLrpTokenException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.InvalidTcnIdChecksumException}
 * @error {@link DomainFlowUtils.InvalidTrademarkValidatorException}
 * @error {@link DomainFlowUtils.LaunchPhaseMismatchException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.MalformedTcnIdException}
 * @error {@link DomainFlowUtils.MaxSigLifeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingClaimsNoticeException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedException}
 * @error {@link DomainFlowUtils.NoMarksFoundMatchingDomainException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.SignedMarksMustBeEncodedException}
 * @error {@link DomainFlowUtils.SignedMarkCertificateExpiredException}
 * @error {@link DomainFlowUtils.SignedMarkCertificateInvalidException}
 * @error {@link DomainFlowUtils.SignedMarkCertificateNotYetValidException}
 * @error {@link DomainFlowUtils.SignedMarkCertificateRevokedException}
 * @error {@link DomainFlowUtils.SignedMarkCertificateSignatureException}
 * @error {@link DomainFlowUtils.SignedMarkEncodingErrorException}
 * @error {@link DomainFlowUtils.SignedMarkParsingErrorException}
 * @error {@link DomainFlowUtils.SignedMarkRevokedErrorException}
 * @error {@link DomainFlowUtils.SignedMarkSignatureException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.TooManySignedMarksException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.UnexpectedClaimsNoticeException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainFlowUtils.UnsupportedMarkTypeException}
 */
public final class DomainApplicationCreateFlow implements TransactionalFlow {

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject AuthInfo authInfo;
  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject Trid trid;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainApplicationCreateFlowCustomLogic customLogic;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainApplicationCreateFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        FeeCreateCommandExtension.class,
        SecDnsCreateExtension.class,
        FlagsCreateCommandExtension.class,
        MetadataExtension.class,
        LaunchCreateExtension.class);
    customLogic.beforeValidation();
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = ofy().getTransactionTime();
    Create command = cloneAndLinkReferences((Create) resourceCommand, now);
    failfastForCreate(targetId, now);
    // Fail if the domain is already registered (e.g. this is a landrush application but the domain
    // was awarded at the end of sunrise). However, multiple domain applications can be created for
    // the same domain name, so don't try to load an existing application.
    verifyResourceDoesNotExist(DomainResource.class, targetId, now);
    // Validate that this is actually a legal domain name on a TLD that the registrar has access to.
    InternetDomainName domainName = validateDomainName(targetId);
    String idnTableName = validateDomainNameWithIdnTables(domainName);
    String tld = domainName.parent().toString();
    checkAllowedAccessToTld(clientId, tld);
    Registry registry = Registry.get(tld);
    FeesAndCredits feesAndCredits =
        pricingLogic.getCreatePrice(registry, targetId, now, command.getPeriod().getValue());
    // Superusers can create reserved domains, force creations on domains that require a claims
    // notice without specifying a claims key, and override blocks on registering premium domains.
    verifyUnitIsYears(command.getPeriod());
    int years = command.getPeriod().getValue();
    validateCreateCommandContactsAndNameservers(command, tld);
    LaunchCreateExtension launchCreate = eppInput.getSingleExtension(LaunchCreateExtension.class);
    if (launchCreate != null) {
      validateLaunchCreateExtension(launchCreate, registry, domainName, now);
    }
    boolean isAnchorTenant =
        matchesAnchorTenantReservation(domainName, authInfo.getPw().getValue());
    if (!isSuperuser) {
      verifyPremiumNameIsNotBlocked(targetId, now, clientId);
      prohibitLandrushIfExactlyOneSunrise(registry, now);
      if (!isAnchorTenant) {
        boolean isSunriseApplication = !launchCreate.getSignedMarks().isEmpty();
        verifyNotReserved(domainName, isSunriseApplication);
      }
    }
    FeeCreateCommandExtension feeCreate =
        eppInput.getSingleExtension(FeeCreateCommandExtension.class);
    validateFeeChallenge(targetId, tld, now, feeCreate, feesAndCredits.getTotalCost());
    SecDnsCreateExtension secDnsCreate =
        validateSecDnsExtension(eppInput.getSingleExtension(SecDnsCreateExtension.class));
    customLogic.afterValidation(
        AfterValidationParameters.newBuilder()
            .setDomainName(domainName)
            .setYears(years)
            .build());
    DomainApplication newApplication = new DomainApplication.Builder()
        .setCreationTrid(trid)
        .setCreationClientId(clientId)
        .setCurrentSponsorClientId(clientId)
        .setRepoId(createDomainRoid(ObjectifyService.allocateId(), tld))
        .setLaunchNotice(launchCreate == null ? null : launchCreate.getNotice())
        .setIdnTableName(idnTableName)
        .setPhase(launchCreate.getPhase())
        .setPeriod(command.getPeriod())
        .setApplicationStatus(ApplicationStatus.VALIDATED)
        .addStatusValue(StatusValue.PENDING_CREATE)
        .setDsData(secDnsCreate == null ? null : secDnsCreate.getDsData())
        .setRegistrant(command.getRegistrant())
        .setAuthInfo(command.getAuthInfo())
        .setFullyQualifiedDomainName(targetId)
        .setNameservers(command.getNameservers())
        .setContacts(command.getContacts())
        .setEncodedSignedMarks(FluentIterable
            .from(launchCreate.getSignedMarks())
            .transform(new Function<AbstractSignedMark, EncodedSignedMark>() {
              @Override
              public EncodedSignedMark apply(AbstractSignedMark abstractSignedMark) {
                return (EncodedSignedMark) abstractSignedMark;
              }})
            .toList())
        .build();
    HistoryEntry historyEntry = buildHistory(newApplication.getRepoId(), command.getPeriod(), now);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    handleExtraFlowLogic(
        registry.getTldStr(), command.getPeriod().getValue(), historyEntry, newApplication);
    entitiesToSave.add(
        newApplication,
        historyEntry,
        DomainApplicationIndex.createUpdatedInstance(newApplication),
        EppResourceIndex.create(Key.create(newApplication)));
    // Anchor tenant registrations override LRP, and landrush applications can skip it.
    // If a token is passed in outside of an LRP phase, it is simply ignored (i.e. never redeemed).
    if (registry.getLrpPeriod().contains(now) && !isAnchorTenant) {
      entitiesToSave.add(
          prepareMarkedLrpTokenEntity(authInfo.getPw().getValue(), domainName, historyEntry));
    }
    EntityChanges entityChanges =
        customLogic.beforeSave(
            DomainApplicationCreateFlowCustomLogic.BeforeSaveParameters.newBuilder()
                .setNewApplication(newApplication)
                .setHistoryEntry(historyEntry)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .setYears(years)
                .build());
    persistEntityChanges(entityChanges);
    BeforeResponseReturnData responseData =
        customLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setResData(DomainCreateData.create(targetId, now, null))
                .setResponseExtensions(
                    createResponseExtensions(
                        newApplication.getForeignKey(),
                        launchCreate.getPhase(),
                        feeCreate,
                        feesAndCredits))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private void validateLaunchCreateExtension(
      LaunchCreateExtension launchCreate,
      Registry registry,
      InternetDomainName domainName,
      DateTime now) throws EppException {
    verifyNoCodeMarks(launchCreate);
    boolean hasClaimsNotice = launchCreate.getNotice() != null;
    if (hasClaimsNotice) {
      verifyClaimsPeriodNotEnded(registry, now);
    }
    boolean isSunriseApplication = !launchCreate.getSignedMarks().isEmpty();
    if (!isSuperuser) {  // Superusers can ignore the phase.
      verifyRegistryStateAllowsLaunchFlows(registry, now);
      verifyLaunchPhaseMatchesRegistryPhase(registry, launchCreate, now);
    }
    if (now.isBefore(registry.getClaimsPeriodEnd())) {
      verifyClaimsNoticeIfAndOnlyIfNeeded(domainName, isSunriseApplication, hasClaimsNotice);
    }
    TldState tldState = registry.getTldState(now);
    if (launchCreate.getSignedMarks().isEmpty()) {
      // During sunrise, a signed mark is required since only trademark holders are allowed to
      // create an application. However, we found no marks (ie, this was a landrush application).
      if (tldState == TldState.SUNRISE) {
        throw new LandrushApplicationDisallowedDuringSunriseException();
      }
    } else {
      if (hasClaimsNotice) {  // Can't use a claims notice id with a signed mark.
        throw new NoticeCannotBeUsedWithSignedMarkException();
      }
      if (tldState == TldState.LANDRUSH) {
        throw new SunriseApplicationDisallowedDuringLandrushException();
      }
    }
    String domainLabel = domainName.parts().get(0);
    validateLaunchCreateNotice(launchCreate.getNotice(), domainLabel, isSuperuser, now);
    // If a signed mark was provided, then it must match the desired domain label.
    if (!launchCreate.getSignedMarks().isEmpty()) {
      verifySignedMarks(launchCreate.getSignedMarks(), domainLabel, now);
    }
  }

  /**
   * Prohibit creating a landrush application in LANDRUSH (but not in SUNRUSH) if there is exactly
   * one sunrise application for the same name.
   */
  private void prohibitLandrushIfExactlyOneSunrise(Registry registry, DateTime now)
      throws UncontestedSunriseApplicationBlockedInLandrushException {
    if (registry.getTldState(now) == TldState.LANDRUSH) {
      ImmutableSet<DomainApplication> applications =
          loadActiveApplicationsByDomainName(targetId, now);
      if (applications.size() == 1
          && getOnlyElement(applications).getPhase().equals(LaunchPhase.SUNRISE)) {
        throw new UncontestedSunriseApplicationBlockedInLandrushException();
      }
    }
  }

  private HistoryEntry buildHistory(String repoId, Period period, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_APPLICATION_CREATE)
        .setPeriod(period)
        .setModificationTime(now)
        .setParent(Key.create(DomainApplication.class, repoId))
        .build();
  }

  private static ImmutableList<ResponseExtension> createResponseExtensions(
      String applicationId,
      LaunchPhase launchPhase,
      FeeTransformCommandExtension feeCreate,
      FeesAndCredits feesAndCredits) {
    ImmutableList.Builder<ResponseExtension> responseExtensionsBuilder =
        new ImmutableList.Builder<>();
    responseExtensionsBuilder.add(new LaunchCreateResponseExtension.Builder()
        .setPhase(launchPhase)
        .setApplicationId(applicationId)
        .build());
    if (feeCreate != null) {
      responseExtensionsBuilder.add(createFeeCreateResponse(feeCreate, feesAndCredits));
    }
    return responseExtensionsBuilder.build();
  }

  private void handleExtraFlowLogic(
      String tld, int years, HistoryEntry historyEntry, DomainApplication newApplication)
          throws EppException {
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForTld(tld);
    if (extraFlowLogic.isPresent()) {
      extraFlowLogic.get().performAdditionalApplicationCreateLogic(
          newApplication,
          clientId,
          years,
          eppInput,
          historyEntry);
    }
  }

  /** Landrush applications are disallowed during sunrise. */
  static class LandrushApplicationDisallowedDuringSunriseException
      extends RequiredParameterMissingException {
    public LandrushApplicationDisallowedDuringSunriseException() {
      super("Landrush applications are disallowed during sunrise");
    }
  }

  /** A notice cannot be specified when using a signed mark. */
  static class NoticeCannotBeUsedWithSignedMarkException extends CommandUseErrorException {
    public NoticeCannotBeUsedWithSignedMarkException() {
      super("A notice cannot be specified when using a signed mark");
    }
  }

  /** Sunrise applications are disallowed during landrush. */
  static class SunriseApplicationDisallowedDuringLandrushException
      extends CommandUseErrorException {
    public SunriseApplicationDisallowedDuringLandrushException() {
      super("Sunrise applications are disallowed during landrush");
    }
  }

  /** This name has already been claimed by a sunrise applicant. */
  static class UncontestedSunriseApplicationBlockedInLandrushException
      extends ObjectAlreadyExistsException {
    public UncontestedSunriseApplicationBlockedInLandrushException() {
      super("This name has already been claimed by a sunrise applicant");
    }
  }
}
