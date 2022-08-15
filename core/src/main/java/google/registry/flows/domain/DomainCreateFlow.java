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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.domain.DomainFlowUtils.COLLISION_MESSAGE;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.createFeeCreateResponse;
import static google.registry.flows.domain.DomainFlowUtils.getReservationTypes;
import static google.registry.flows.domain.DomainFlowUtils.isAnchorTenant;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.flows.domain.DomainFlowUtils.isValidReservedCreate;
import static google.registry.flows.domain.DomainFlowUtils.validateCreateCommandContactsAndNameservers;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.validateLaunchCreateNotice;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrationPeriod;
import static google.registry.flows.domain.DomainFlowUtils.validateSecDnsExtension;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsNoticeIfAndOnlyIfNeeded;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsPeriodNotEnded;
import static google.registry.flows.domain.DomainFlowUtils.verifyLaunchPhaseMatchesRegistryPhase;
import static google.registry.flows.domain.DomainFlowUtils.verifyNoCodeMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistrarIsActive;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.IdService.allocateId;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.tld.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Registry.TldState.QUIET_PERIOD;
import static google.registry.model.tld.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.model.tld.label.ReservationType.NAME_COLLISION;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainCreateFlowCustomLogic;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldState;
import google.registry.model.tld.Registry.TldType;
import google.registry.model.tld.label.ReservationType;
import google.registry.model.tmch.ClaimsList;
import google.registry.model.tmch.ClaimsListDao;
import google.registry.tmch.LordnTaskUtils;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * An EPP flow that creates a new domain resource.
 *
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException}
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link ResourceAlreadyExistsForThisClientException}
 * @error {@link ResourceCreateContentionException}
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link DomainCreateFlow.AnchorTenantCreatePeriodException}
 * @error {@link DomainCreateFlow.MustHaveSignedMarksInCurrentPhaseException}
 * @error {@link DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException}
 * @error {@link DomainCreateFlow.NoTrademarkedRegistrationsBeforeSunriseException}
 * @error {@link DomainCreateFlow.SignedMarksOnlyDuringSunriseException}
 * @error {@link DomainFlowTmchUtils.NoMarksFoundMatchingDomainException}
 * @error {@link DomainFlowTmchUtils.FoundMarkNotYetValidException}
 * @error {@link DomainFlowTmchUtils.FoundMarkExpiredException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.AcceptedTooLongAgoException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.DomainNameExistsAsTldException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.ClaimsPeriodEndedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.ExceedsMaxRegistrationYearsException}
 * @error {@link DomainFlowUtils.ExpiredClaimException}
 * @error {@link DomainFlowUtils.FeeDescriptionMultipleMatchesException}
 * @error {@link DomainFlowUtils.FeeDescriptionParseException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredDuringEarlyAccessProgramException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.InvalidDsRecordException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.InvalidTcnIdChecksumException}
 * @error {@link DomainFlowUtils.InvalidTrademarkValidatorException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MalformedTcnIdException}
 * @error {@link DomainFlowUtils.MaxSigLifeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.MissingClaimsNoticeException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingRegistrantException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedForTldException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.RegistrarMustBeActiveForThisOperationException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.UnexpectedClaimsNoticeException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainFlowUtils.UnsupportedMarkTypeException}
 * @error {@link DomainPricingLogic.AllocationTokenInvalidForPremiumNameException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_CREATE)
public final class DomainCreateFlow implements TransactionalFlow {

  /** Anchor tenant creates should always be for 2 years, since they get 2 years free. */
  private static final int ANCHOR_TENANT_CREATE_VALID_YEARS = 2;

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject ResourceCommand resourceCommand;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject AllocationTokenFlowUtils allocationTokenFlowUtils;
  @Inject DomainCreateFlowCustomLogic flowCustomLogic;
  @Inject DomainFlowTmchUtils tmchUtils;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DnsQueue dnsQueue;
  @Inject DomainCreateFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeCreateCommandExtension.class,
        SecDnsCreateExtension.class,
        MetadataExtension.class,
        LaunchCreateExtension.class,
        AllocationTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    verifyRegistrarIsActive(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    DomainCommand.Create command = cloneAndLinkReferences((Create) resourceCommand, now);
    Period period = command.getPeriod();
    verifyUnitIsYears(period);
    int years = period.getValue();
    validateRegistrationPeriod(years);
    verifyResourceDoesNotExist(Domain.class, targetId, now, registrarId);
    // Validate that this is actually a legal domain name on a TLD that the registrar has access to.
    InternetDomainName domainName = validateDomainName(command.getFullyQualifiedDomainName());
    String domainLabel = domainName.parts().get(0);
    Registry registry = Registry.get(domainName.parent().toString());
    validateCreateCommandContactsAndNameservers(command, registry, domainName);
    TldState tldState = registry.getTldState(now);
    Optional<LaunchCreateExtension> launchCreate =
        eppInput.getSingleExtension(LaunchCreateExtension.class);
    boolean hasSignedMarks =
        launchCreate.isPresent() && !launchCreate.get().getSignedMarks().isEmpty();
    boolean hasClaimsNotice = launchCreate.isPresent() && launchCreate.get().getNotice() != null;
    if (launchCreate.isPresent()) {
      verifyNoCodeMarks(launchCreate.get());
      validateLaunchCreateNotice(launchCreate.get().getNotice(), domainLabel, isSuperuser, now);
    }
    boolean isSunriseCreate = hasSignedMarks && (tldState == START_DATE_SUNRISE);
    Optional<AllocationToken> allocationToken =
        allocationTokenFlowUtils.verifyAllocationTokenCreateIfPresent(
            command,
            registry,
            registrarId,
            now,
            eppInput.getSingleExtension(AllocationTokenExtension.class));
    boolean isAnchorTenant =
        isAnchorTenant(
            domainName, allocationToken, eppInput.getSingleExtension(MetadataExtension.class));
    verifyAnchorTenantValidPeriod(isAnchorTenant, years);
    // Superusers can create reserved domains, force creations on domains that require a claims
    // notice without specifying a claims key, ignore the registry phase, and override blocks on
    // registering premium domains.
    if (!isSuperuser) {
      checkAllowedAccessToTld(registrarId, registry.getTldStr());
      checkHasBillingAccount(registrarId, registry.getTldStr());
      boolean isValidReservedCreate = isValidReservedCreate(domainName, allocationToken);
      ClaimsList claimsList = ClaimsListDao.get();
      verifyIsGaOrSpecialCase(
          registry,
          claimsList,
          now,
          domainLabel,
          allocationToken,
          isAnchorTenant,
          isValidReservedCreate,
          hasSignedMarks);
      if (launchCreate.isPresent()) {
        verifyLaunchPhaseMatchesRegistryPhase(registry, launchCreate.get(), now);
      }
      if (!isAnchorTenant && !isValidReservedCreate) {
        verifyNotReserved(domainName, isSunriseCreate);
      }
      if (hasClaimsNotice) {
        verifyClaimsPeriodNotEnded(registry, now);
      }
      if (now.isBefore(registry.getClaimsPeriodEnd())) {
        verifyClaimsNoticeIfAndOnlyIfNeeded(
            domainName, claimsList, hasSignedMarks, hasClaimsNotice);
      }
      verifyPremiumNameIsNotBlocked(targetId, now, registrarId);
      verifySignedMarkOnlyInSunrise(hasSignedMarks, tldState);
    }
    String signedMarkId = null;
    if (hasSignedMarks) {
      // If a signed mark was provided, then it must match the desired domain label. Get the mark
      // at this point so that we can verify it before the "after validation" extension point.
      signedMarkId =
          tmchUtils
              .verifySignedMarks(launchCreate.get().getSignedMarks(), domainLabel, now)
              .getId();
    }
    flowCustomLogic.afterValidation(
        DomainCreateFlowCustomLogic.AfterValidationParameters.newBuilder()
            .setDomainName(domainName)
            .setYears(years)
            .setSignedMarkId(Optional.ofNullable(signedMarkId))
            .build());
    Optional<FeeCreateCommandExtension> feeCreate =
        eppInput.getSingleExtension(FeeCreateCommandExtension.class);
    FeesAndCredits feesAndCredits =
        pricingLogic.getCreatePrice(
            registry, targetId, now, years, isAnchorTenant, allocationToken);
    validateFeeChallenge(targetId, now, feeCreate, feesAndCredits);
    Optional<SecDnsCreateExtension> secDnsCreate =
        validateSecDnsExtension(eppInput.getSingleExtension(SecDnsCreateExtension.class));
    DateTime registrationExpirationTime = leapSafeAddYears(now, years);
    String repoId = createDomainRepoId(allocateId(), registry.getTldStr());
    long historyRevisionId = allocateId();
    DomainHistoryId domainHistoryId = new DomainHistoryId(repoId, historyRevisionId);
    historyBuilder.setId(historyRevisionId);
    // Bill for the create.
    BillingEvent.OneTime createBillingEvent =
        createOneTimeBillingEvent(
            registry,
            isAnchorTenant,
            isSunriseCreate,
            isReserved(domainName, isSunriseCreate),
            years,
            feesAndCredits,
            domainHistoryId,
            allocationToken,
            now);
    // Create a new autorenew billing event and poll message starting at the expiration time.
    BillingEvent.Recurring autorenewBillingEvent =
        createAutorenewBillingEvent(
            domainHistoryId,
            registrationExpirationTime,
            getRenewalPriceInfo(isAnchorTenant, allocationToken, feesAndCredits));
    PollMessage.Autorenew autorenewPollMessage =
        createAutorenewPollMessage(domainHistoryId, registrationExpirationTime);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(createBillingEvent, autorenewBillingEvent, autorenewPollMessage);
    // Bill for EAP cost, if any.
    if (!feesAndCredits.getEapCost().isZero()) {
      entitiesToSave.add(createEapBillingEvent(feesAndCredits, createBillingEvent));
    }

    ImmutableSet<ReservationType> reservationTypes = getReservationTypes(domainName);
    ImmutableSet<StatusValue> statuses =
        reservationTypes.contains(NAME_COLLISION)
            ? ImmutableSet.of(SERVER_HOLD)
            : ImmutableSet.of();
    Domain domain =
        new Domain.Builder()
            .setCreationRegistrarId(registrarId)
            .setPersistedCurrentSponsorRegistrarId(registrarId)
            .setRepoId(repoId)
            .setIdnTableName(validateDomainNameWithIdnTables(domainName))
            .setRegistrationExpirationTime(registrationExpirationTime)
            .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
            .setAutorenewPollMessage(
                autorenewPollMessage.createVKey(), autorenewPollMessage.getHistoryRevisionId())
            .setLaunchNotice(hasClaimsNotice ? launchCreate.get().getNotice() : null)
            .setSmdId(signedMarkId)
            .setDsData(secDnsCreate.map(SecDnsCreateExtension::getDsData).orElse(null))
            .setRegistrant(command.getRegistrant())
            .setAuthInfo(command.getAuthInfo())
            .setDomainName(targetId)
            .setNameservers(command.getNameservers().stream().collect(toImmutableSet()))
            .setStatusValues(statuses)
            .setContacts(command.getContacts())
            .addGracePeriod(
                GracePeriod.forBillingEvent(GracePeriodStatus.ADD, repoId, createBillingEvent))
            .build();
    DomainHistory domainHistory =
        buildDomainHistory(domain, registry, now, period, registry.getAddGracePeriodLength());
    if (reservationTypes.contains(NAME_COLLISION)) {
      entitiesToSave.add(
          createNameCollisionOneTimePollMessage(targetId, domainHistory, registrarId, now));
    }
    entitiesToSave.add(
        domain,
        domainHistory,
        ForeignKeyIndex.create(domain, domain.getDeletionTime()),
        EppResourceIndex.create(Key.create(domain)));
    if (allocationToken.isPresent()
        && TokenType.SINGLE_USE.equals(allocationToken.get().getTokenType())) {
      entitiesToSave.add(
          allocationTokenFlowUtils.redeemToken(allocationToken.get(), domainHistory.createVKey()));
    }
    enqueueTasks(domain, hasSignedMarks, hasClaimsNotice);

    EntityChanges entityChanges =
        flowCustomLogic.beforeSave(
            DomainCreateFlowCustomLogic.BeforeSaveParameters.newBuilder()
                .setNewDomain(domain)
                .setHistoryEntry(domainHistory)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .setYears(years)
                .build());
    persistEntityChanges(entityChanges);

    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setResData(DomainCreateData.create(targetId, now, registrationExpirationTime))
                .setResponseExtensions(createResponseExtensions(feeCreate, feesAndCredits))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  /**
   * Verifies that signed marks are only sent during sunrise.
   *
   * <p>A trademarked domain name requires either a signed mark or a claims notice. We then need to
   * send out a LORDN message - either a "sunrise" LORDN if we have a signed mark, or a "claims"
   * LORDN if we have a claims notice.
   *
   * <p>This verification prevents us from either sending out a "sunrise" LORDN out of sunrise, or
   * not sending out any LORDN, for a trademarked domain with a signed mark in GA.
   */
  static void verifySignedMarkOnlyInSunrise(boolean hasSignedMarks, TldState tldState)
      throws EppException {
    if (hasSignedMarks && tldState != START_DATE_SUNRISE) {
      throw new SignedMarksOnlyDuringSunriseException();
    }
  }

  /**
   * Verifies anchor tenant creates are only done for {@value ANCHOR_TENANT_CREATE_VALID_YEARS} year
   * periods, as anchor tenants get exactly that many years of free registration.
   */
  static void verifyAnchorTenantValidPeriod(boolean isAnchorTenant, int registrationYears)
      throws EppException {
    if (isAnchorTenant && registrationYears != ANCHOR_TENANT_CREATE_VALID_YEARS) {
      throw new AnchorTenantCreatePeriodException(registrationYears);
    }
  }

  /**
   * Prohibit registrations unless they're in GA or a special case.
   *
   * <p>Non-trademarked names can be registered at any point with a special allocation token
   * registration behavior.
   *
   * <p>Trademarked names require signed marks in sunrise no matter what, and can be registered with
   * a special allocation token behavior in any quiet period that is post-sunrise.
   *
   * <p>Note that "superuser" status isn't tested here - this should only be called for
   * non-superusers.
   */
  private void verifyIsGaOrSpecialCase(
      Registry registry,
      ClaimsList claimsList,
      DateTime now,
      String domainLabel,
      Optional<AllocationToken> allocationToken,
      boolean isAnchorTenant,
      boolean isValidReservedCreate,
      boolean hasSignedMarks)
      throws NoGeneralRegistrationsInCurrentPhaseException,
          MustHaveSignedMarksInCurrentPhaseException,
          NoTrademarkedRegistrationsBeforeSunriseException {
    // We allow general registration during GA.
    TldState currentState = registry.getTldState(now);
    if (currentState.equals(GENERAL_AVAILABILITY)) {
      return;
    }

    // Determine if there should be any behavior dictated by the allocation token
    RegistrationBehavior behavior =
        allocationToken
            .map(AllocationToken::getRegistrationBehavior)
            .orElse(RegistrationBehavior.DEFAULT);
    // Bypass most TLD state checks if that behavior is specified by the token
    if (behavior.equals(RegistrationBehavior.BYPASS_TLD_STATE)
        || behavior.equals(RegistrationBehavior.ANCHOR_TENANT)) {
      // Non-trademarked names with the state check bypassed are always available
      if (!claimsList.getClaimKey(domainLabel).isPresent()) {
        return;
      }
      if (!currentState.equals(START_DATE_SUNRISE)) {
        // Trademarked domains cannot be registered until after the sunrise period has ended, unless
        // a valid signed mark is provided. Signed marks can only be provided during sunrise.
        // Thus, when bypassing TLD state checks, a post-sunrise state is always fine.
        if (registry.getTldStateTransitions().headMap(now).containsValue(START_DATE_SUNRISE)) {
          return;
        } else {
          // If sunrise hasn't happened yet, trademarked domains are unavailable
          throw new NoTrademarkedRegistrationsBeforeSunriseException(domainLabel);
        }
      }
    }

    // Otherwise, signed marks are necessary and sufficient in the sunrise period
    if (currentState.equals(START_DATE_SUNRISE)) {
      if (!hasSignedMarks) {
        throw new MustHaveSignedMarksInCurrentPhaseException();
      }
      return;
    }

    // Anchor tenant overrides any remaining considerations to allow registration
    if (isAnchorTenant) {
      return;
    }

    // We allow creates of specifically reserved domain names during quiet periods
    if (currentState.equals(QUIET_PERIOD)) {
      if (isValidReservedCreate) {
        return;
      }
    }
    // All other phases do not allow registration
    throw new NoGeneralRegistrationsInCurrentPhaseException();
  }

  private DomainHistory buildDomainHistory(
      Domain domain, Registry registry, DateTime now, Period period, Duration addGracePeriod) {
    // We ignore prober transactions
    if (registry.getTldType() == TldType.REAL) {
      historyBuilder
          .setDomainTransactionRecords(
              ImmutableSet.of(
                  DomainTransactionRecord.create(
                      registry.getTldStr(),
                      now.plus(addGracePeriod),
                      TransactionReportField.netAddsFieldFromYears(period.getValue()),
                      1)));
    }
    return historyBuilder.setType(DOMAIN_CREATE).setPeriod(period).setDomain(domain).build();
  }

  private BillingEvent.OneTime createOneTimeBillingEvent(
      Registry registry,
      boolean isAnchorTenant,
      boolean isSunriseCreate,
      boolean isReserved,
      int years,
      FeesAndCredits feesAndCredits,
      DomainHistoryId domainHistoryId,
      Optional<AllocationToken> allocationToken,
      DateTime now) {
    ImmutableSet.Builder<Flag> flagsBuilder = new ImmutableSet.Builder<>();
    // Sunrise and anchor tenancy are orthogonal tags and thus both can be present together.
    if (isSunriseCreate) {
      flagsBuilder.add(Flag.SUNRISE);
    }
    if (isAnchorTenant) {
      flagsBuilder.add(Flag.ANCHOR_TENANT);
    } else if (isReserved) {
      // Don't add this flag if the domain is an anchor tenant (which are also reserved); only add
      // it if it's reserved for other reasons.
      flagsBuilder.add(Flag.RESERVED);
    }
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setPeriodYears(years)
        .setCost(feesAndCredits.getCreateCost())
        .setEventTime(now)
        .setAllocationToken(allocationToken.map(AllocationToken::createVKey).orElse(null))
        .setBillingTime(
            now.plus(
                isAnchorTenant
                    ? registry.getAnchorTenantAddGracePeriodLength()
                    : registry.getAddGracePeriodLength()))
        .setFlags(flagsBuilder.build())
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private Recurring createAutorenewBillingEvent(
      DomainHistoryId domainHistoryId,
      DateTime registrationExpirationTime,
      RenewalPriceInfo renewalpriceInfo) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setEventTime(registrationExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistoryId(domainHistoryId)
        .setRenewalPriceBehavior(renewalpriceInfo.renewalPriceBehavior())
        .setRenewalPrice(renewalpriceInfo.renewalPrice())
        .build();
  }

  private Autorenew createAutorenewPollMessage(
      DomainHistoryId domainHistoryId, DateTime registrationExpirationTime) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setEventTime(registrationExpirationTime)
        .setMsg("Domain was auto-renewed.")
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private static BillingEvent.OneTime createEapBillingEvent(
      FeesAndCredits feesAndCredits, BillingEvent.OneTime createBillingEvent) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.FEE_EARLY_ACCESS)
        .setTargetId(createBillingEvent.getTargetId())
        .setRegistrarId(createBillingEvent.getRegistrarId())
        .setPeriodYears(1)
        .setCost(feesAndCredits.getEapCost())
        .setEventTime(createBillingEvent.getEventTime())
        .setBillingTime(createBillingEvent.getBillingTime())
        .setFlags(createBillingEvent.getFlags())
        .setDomainHistoryId(createBillingEvent.getDomainHistoryId())
        .build();
  }

  private static PollMessage.OneTime createNameCollisionOneTimePollMessage(
      String fullyQualifiedDomainName,
      HistoryEntry historyEntry,
      String registrarId,
      DateTime now) {
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(registrarId)
        .setEventTime(now)
        .setMsg(COLLISION_MESSAGE) // Remind the registrar of the name collision policy.
        .setResponseData(
            ImmutableList.of(
                DomainPendingActionNotificationResponse.create(
                    fullyQualifiedDomainName, true, historyEntry.getTrid(), now)))
        .setHistoryEntry(historyEntry)
        .build();
  }

  private void enqueueTasks(Domain newDomain, boolean hasSignedMarks, boolean hasClaimsNotice) {
    if (newDomain.shouldPublishToDns()) {
      dnsQueue.addDomainRefreshTask(newDomain.getDomainName());
    }
    if (hasClaimsNotice || hasSignedMarks) {
      LordnTaskUtils.enqueueDomainTask(newDomain);
    }
  }

  /**
   * Determines the {@link RenewalPriceBehavior} and the renewal price that needs be stored in the
   * {@link Recurring} billing events.
   *
   * <p>By default, the renewal price is calculated during the process of renewal. Renewal price
   * should be the createCost if and only if the renewal price behavior in the {@link
   * AllocationToken} is 'SPECIFIED'.
   */
  static RenewalPriceInfo getRenewalPriceInfo(
      boolean isAnchorTenant,
      Optional<AllocationToken> allocationToken,
      FeesAndCredits feesAndCredits) {
    if (isAnchorTenant) {
      if (allocationToken.isPresent()) {
        checkArgument(
            allocationToken.get().getRenewalPriceBehavior() != RenewalPriceBehavior.SPECIFIED,
            "Renewal price behavior cannot be SPECIFIED for anchor tenant");
      }
      return RenewalPriceInfo.create(RenewalPriceBehavior.NONPREMIUM, null);
    } else if (allocationToken.isPresent()
        && allocationToken.get().getRenewalPriceBehavior() == RenewalPriceBehavior.SPECIFIED) {
      return RenewalPriceInfo.create(
          RenewalPriceBehavior.SPECIFIED, feesAndCredits.getCreateCost());
    } else {
      return RenewalPriceInfo.create(RenewalPriceBehavior.DEFAULT, null);
    }
  }

  /** A class to store renewal info used in {@link Recurring} billing events. */
  @AutoValue
  public abstract static class RenewalPriceInfo {
    static DomainCreateFlow.RenewalPriceInfo create(
        RenewalPriceBehavior renewalPriceBehavior, @Nullable Money renewalPrice) {
      return new AutoValue_DomainCreateFlow_RenewalPriceInfo(renewalPriceBehavior, renewalPrice);
    }

    public abstract RenewalPriceBehavior renewalPriceBehavior();

    @Nullable
    public abstract Money renewalPrice();
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      Optional<FeeCreateCommandExtension> feeCreate, FeesAndCredits feesAndCredits) {
    return feeCreate.isPresent()
        ? ImmutableList.of(createFeeCreateResponse(feeCreate.get(), feesAndCredits))
        : ImmutableList.of();
  }

  /** Signed marks are only allowed during sunrise. */
  static class SignedMarksOnlyDuringSunriseException extends CommandUseErrorException {
    public SignedMarksOnlyDuringSunriseException() {
      super("Signed marks are only allowed during sunrise");
    }
  }

  /** The current registry phase does not allow for general registrations. */
  static class NoGeneralRegistrationsInCurrentPhaseException extends CommandUseErrorException {
    public NoGeneralRegistrationsInCurrentPhaseException() {
      super("The current registry phase does not allow for general registrations");
    }
  }

  /** The current registry phase allows registrations only with signed marks. */
  static class MustHaveSignedMarksInCurrentPhaseException extends CommandUseErrorException {
    public MustHaveSignedMarksInCurrentPhaseException() {
      super("The current registry phase requires a signed mark for registrations");
    }
  }

  /** Trademarked domains cannot be registered before the sunrise period. */
  static class NoTrademarkedRegistrationsBeforeSunriseException
      extends ParameterValuePolicyErrorException {
    public NoTrademarkedRegistrationsBeforeSunriseException(String domainLabel) {
      super(
          String.format(
              "The trademarked label %s cannot be registered before the sunrise period.",
              domainLabel));
    }
  }

  /** Anchor tenant domain create is for the wrong number of years. */
  static class AnchorTenantCreatePeriodException extends ParameterValuePolicyErrorException {
    public AnchorTenantCreatePeriodException(int invalidYears) {
      super(
          String.format(
              "Anchor tenant domain creates must be for a period of %s years, got %s instead.",
              ANCHOR_TENANT_CREATE_VALID_YEARS, invalidYears));
    }
  }
}
