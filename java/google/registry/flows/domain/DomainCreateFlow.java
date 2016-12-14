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
import static google.registry.flows.domain.DomainFlowUtils.verifySignedMarks;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRoid;
import static google.registry.model.index.DomainApplicationIndex.loadActiveApplicationsByDomainName;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.ReservedList.matchesAnchorTenantReservation;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.custom.DomainCreateFlowCustomLogic;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainCreateFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainPricingLogic.FeesAndCredits;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.flags.FlagsCreateCommandExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.tmch.LordnTask;
import java.util.Set;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that creates a new domain resource.
 *
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link google.registry.flows.exceptions.ResourceAlreadyExistsException}
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException}
 * @error {@link google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.AcceptedTooLongAgoException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
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
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MalformedTcnIdException}
 * @error {@link DomainFlowUtils.MaxSigLifeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingClaimsNoticeException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingRegistrantException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.UnexpectedClaimsNoticeException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainFlowUtils.UnsupportedMarkTypeException}
 * @error {@link DomainCreateFlow.DomainHasOpenApplicationsException}
 * @error {@link DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException}
 */

public class DomainCreateFlow implements TransactionalFlow {

  private static final Set<TldState> SUNRISE_STATES =
      Sets.immutableEnumSet(TldState.SUNRISE, TldState.SUNRUSH);

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject AuthInfo authInfo;
  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainCreateFlowCustomLogic customLogic;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DnsQueue dnsQueue;
  @Inject DomainCreateFlow() {}

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
    Period period = command.getPeriod();
    verifyUnitIsYears(period);
    int years = period.getValue();
    failfastForCreate(targetId, now);
    verifyResourceDoesNotExist(DomainResource.class, targetId, now);
    // Validate that this is actually a legal domain name on a TLD that the registrar has access to.
    InternetDomainName domainName = validateDomainName(command.getFullyQualifiedDomainName());
    String domainLabel = domainName.parts().get(0);
    Registry registry = Registry.get(domainName.parent().toString());
    validateCreateCommandContactsAndNameservers(command, registry.getTldStr());
    TldState tldState = registry.getTldState(now);
    boolean isAnchorTenant = isAnchorTenant(domainName);
    LaunchCreateExtension launchCreate = eppInput.getSingleExtension(LaunchCreateExtension.class);
    boolean hasSignedMarks = launchCreate != null && !launchCreate.getSignedMarks().isEmpty();
    boolean hasClaimsNotice = launchCreate != null && launchCreate.getNotice() != null;
    if (launchCreate != null) {
      verifyNoCodeMarks(launchCreate);
      validateLaunchCreateNotice(launchCreate.getNotice(), domainLabel, isSuperuser, now);
    }
    boolean isSunriseCreate = hasSignedMarks && SUNRISE_STATES.contains(tldState);
    customLogic.afterValidation(
        DomainCreateFlowCustomLogic.AfterValidationParameters.newBuilder()
            .setDomainName(domainName)
            .setYears(years)
            .build());

    FeeCreateCommandExtension feeCreate =
        eppInput.getSingleExtension(FeeCreateCommandExtension.class);
    FeesAndCredits feesAndCredits = pricingLogic.getCreatePrice(registry, targetId, now, years);
    validateFeeChallenge(
        targetId, registry.getTldStr(), now, feeCreate, feesAndCredits.getTotalCost());
    // Superusers can create reserved domains, force creations on domains that require a claims
    // notice without specifying a claims key, ignore the registry phase, and override blocks on
    // registering premium domains.
    if (!isSuperuser) {
      checkAllowedAccessToTld(clientId, registry.getTldStr());
      if (launchCreate != null) {
        verifyLaunchPhaseMatchesRegistryPhase(registry, launchCreate, now);
      }
      if (!isAnchorTenant) {
        verifyNotReserved(domainName, isSunriseCreate);
      }
      if (hasClaimsNotice) {
        verifyClaimsPeriodNotEnded(registry, now);
      }
      if (now.isBefore(registry.getClaimsPeriodEnd())) {
        verifyClaimsNoticeIfAndOnlyIfNeeded(domainName, hasSignedMarks, hasClaimsNotice);
      }
      verifyPremiumNameIsNotBlocked(targetId, now, clientId);
      verifyNoOpenApplications(now);
      verifyIsGaOrIsSpecialCase(tldState, isAnchorTenant);
    }
    SecDnsCreateExtension secDnsCreate =
        validateSecDnsExtension(eppInput.getSingleExtension(SecDnsCreateExtension.class));
    String repoId = createDomainRoid(ObjectifyService.allocateId(), registry.getTldStr());
    DateTime registrationExpirationTime = leapSafeAddYears(now, years);
    HistoryEntry historyEntry = buildHistory(repoId, period, now);
    // Bill for the create.
    BillingEvent.OneTime createBillingEvent =
        createOneTimeBillingEvent(
            registry, isAnchorTenant, years, feesAndCredits, historyEntry, now);
    // Create a new autorenew billing event and poll message starting at the expiration time.
    BillingEvent.Recurring autorenewBillingEvent =
        createAutorenewBillingEvent(historyEntry, registrationExpirationTime);
    PollMessage.Autorenew autorenewPollMessage =
        createAutorenewPollMessage(historyEntry, registrationExpirationTime);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(
        historyEntry,
        createBillingEvent,
        autorenewBillingEvent,
        autorenewPollMessage);
    // Bill for EAP cost, if any.
    if (!feesAndCredits.getEapCost().isZero()) {
      entitiesToSave.add(createEapBillingEvent(feesAndCredits, createBillingEvent));
    }
    DomainResource newDomain = new DomainResource.Builder()
        .setCreationClientId(clientId)
        .setCurrentSponsorClientId(clientId)
        .setRepoId(repoId)
        .setIdnTableName(validateDomainNameWithIdnTables(domainName))
        .setRegistrationExpirationTime(registrationExpirationTime)
        .setAutorenewBillingEvent(Key.create(autorenewBillingEvent))
        .setAutorenewPollMessage(Key.create(autorenewPollMessage))
        .setLaunchNotice(hasClaimsNotice ? launchCreate.getNotice() : null)
        .setSmdId(hasSignedMarks
            // If a signed mark was provided, then it must match the desired domain label.
            ? verifySignedMarks(launchCreate.getSignedMarks(), domainLabel, now).getId()
            : null)
        .setDsData(secDnsCreate == null ? null : secDnsCreate.getDsData())
        .setRegistrant(command.getRegistrant())
        .setAuthInfo(command.getAuthInfo())
        .setFullyQualifiedDomainName(targetId)
        .setNameservers(command.getNameservers())
        .setContacts(command.getContacts())
        .addGracePeriod(GracePeriod.forBillingEvent(GracePeriodStatus.ADD, createBillingEvent))
        .build();
    entitiesToSave.add(
        newDomain,
        ForeignKeyIndex.create(newDomain, newDomain.getDeletionTime()),
        EppResourceIndex.create(Key.create(newDomain)));

    // Anchor tenant registrations override LRP, and landrush applications can skip it.
    // If a token is passed in outside of an LRP phase, it is simply ignored (i.e. never redeemed).
    if (isLrpCreate(registry, isAnchorTenant, now)) {
      entitiesToSave.add(
          prepareMarkedLrpTokenEntity(authInfo.getPw().getValue(), domainName, historyEntry));
    }
    enqueueTasks(isSunriseCreate, hasClaimsNotice, newDomain);

    EntityChanges entityChanges =
        customLogic.beforeSave(
            DomainCreateFlowCustomLogic.BeforeSaveParameters.newBuilder()
                .setNewDomain(newDomain)
                .setHistoryEntry(historyEntry)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .setYears(years)
                .build());
    persistEntityChanges(entityChanges);

    BeforeResponseReturnData responseData =
        customLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setResData(DomainCreateData.create(targetId, now, registrationExpirationTime))
                .setResponseExtensions(createResponseExtensions(feeCreate, feesAndCredits))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private boolean isAnchorTenant(InternetDomainName domainName) {
    MetadataExtension metadataExtension = eppInput.getSingleExtension(MetadataExtension.class);
    return matchesAnchorTenantReservation(domainName, authInfo.getPw().getValue())
        || (metadataExtension != null && metadataExtension.getIsAnchorTenant());
  }

  /** Prohibit creating a domain if there is an open application for the same name. */
  private void verifyNoOpenApplications(DateTime now) throws DomainHasOpenApplicationsException {
    for (DomainApplication application : loadActiveApplicationsByDomainName(targetId, now)) {
      if (!application.getApplicationStatus().isFinalStatus()) {
        throw new DomainHasOpenApplicationsException();
      }
    }
  }

  /** Prohibit registrations for non-qlp and non-superuser outside of GA. **/
  private void verifyIsGaOrIsSpecialCase(TldState tldState, boolean isAnchorTenant)
      throws NoGeneralRegistrationsInCurrentPhaseException {
    if (!isAnchorTenant && tldState != TldState.GENERAL_AVAILABILITY) {
      throw new NoGeneralRegistrationsInCurrentPhaseException();
    }
  }

  private HistoryEntry buildHistory(String repoId, Period period, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_CREATE)
        .setPeriod(period)
        .setModificationTime(now)
        .setParent(Key.create(DomainResource.class, repoId))
        .build();
  }

  private OneTime createOneTimeBillingEvent(
      Registry registry,
      boolean isAnchorTenant,
      int years,
      FeesAndCredits feesAndCredits,
      HistoryEntry historyEntry,
      DateTime now) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setTargetId(targetId)
        .setClientId(clientId)
        .setPeriodYears(years)
        .setCost(feesAndCredits.getCreateCost())
        .setEventTime(now)
        .setBillingTime(
            now.plus(
                isAnchorTenant
                    ? registry.getAnchorTenantAddGracePeriodLength()
                    : registry.getAddGracePeriodLength()))
        .setFlags(
            isAnchorTenant
                ? ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT)
                : ImmutableSet.<BillingEvent.Flag>of())
        .setParent(historyEntry)
        .build();
  }

  private Recurring createAutorenewBillingEvent(
      HistoryEntry historyEntry, DateTime registrationExpirationTime) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setClientId(clientId)
        .setEventTime(registrationExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
  }

  private Autorenew createAutorenewPollMessage(
      HistoryEntry historyEntry, DateTime registrationExpirationTime) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setClientId(clientId)
        .setEventTime(registrationExpirationTime)
        .setMsg("Domain was auto-renewed.")
        .setParent(historyEntry)
        .build();
  }

  private static OneTime createEapBillingEvent(
      FeesAndCredits feesAndCredits, BillingEvent.OneTime createBillingEvent) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.FEE_EARLY_ACCESS)
        .setTargetId(createBillingEvent.getTargetId())
        .setClientId(createBillingEvent.getClientId())
        .setCost(feesAndCredits.getEapCost())
        .setEventTime(createBillingEvent.getEventTime())
        .setBillingTime(createBillingEvent.getBillingTime())
        .setFlags(createBillingEvent.getFlags())
        .setParent(createBillingEvent.getParentKey())
        .build();
  }

  private boolean isLrpCreate(Registry registry, boolean isAnchorTenant, DateTime now) {
    return registry.getLrpPeriod().contains(now) && !isAnchorTenant;
  }

  private void enqueueTasks(
      boolean isSunriseCreate, boolean hasClaimsNotice, DomainResource newDomain) {
    if (newDomain.shouldPublishToDns()) {
      dnsQueue.addDomainRefreshTask(newDomain.getFullyQualifiedDomainName());
    }
    if (hasClaimsNotice || isSunriseCreate) {
      LordnTask.enqueueDomainResourceTask(newDomain);
    }
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      FeeCreateCommandExtension feeCreate, FeesAndCredits feesAndCredits) {
    return (feeCreate == null)
        ? ImmutableList.<FeeTransformResponseExtension>of()
        : ImmutableList.of(createFeeCreateResponse(feeCreate, feesAndCredits));
  }

  /** There is an open application for this domain. */
  static class DomainHasOpenApplicationsException extends StatusProhibitsOperationException {
    public DomainHasOpenApplicationsException() {
      super("There is an open application for this domain");
    }
  }

  /** The current registry phase does not allow for general registrations. */
  static class NoGeneralRegistrationsInCurrentPhaseException extends CommandUseErrorException {
    public NoGeneralRegistrationsInCurrentPhaseException() {
      super("The current registry phase does not allow for general registrations");
    }
  }
}
