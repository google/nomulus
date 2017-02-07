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

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.createFeeCreateResponse;
import static google.registry.flows.domain.DomainFlowUtils.failfastForCreate;
import static google.registry.flows.domain.DomainFlowUtils.getReservationType;
import static google.registry.flows.domain.DomainFlowUtils.prepareMarkedLrpTokenEntity;
import static google.registry.flows.domain.DomainFlowUtils.validateCreateCommandContactsAndNameservers;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.validateSecDnsExtension;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.EppResourceUtils.createDomainRepoId;
import static google.registry.model.EppResourceUtils.loadDomainApplication;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.ReservedList.matchesAnchorTenantReservation;
import static google.registry.pricing.PricingEngineProxy.getDomainCreateCost;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import dagger.Lazy;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainCommand.Create;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.allocate.AllocateCreateExtension;
import google.registry.model.domain.fee.FeeCreateCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchInfoResponseExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.ReservationType;
import google.registry.model.reporting.HistoryEntry;
import google.registry.tmch.LordnTask;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that allocates a new domain resource from a domain application.
 *
 * @error {@link google.registry.flows.exceptions.ResourceAlreadyExistsException}
 * @error {@link DomainAllocateFlow.HasFinalStatusException}
 * @error {@link DomainAllocateFlow.MissingApplicationException}
 * @error {@link DomainAllocateFlow.OnlySuperuserCanAllocateException}
 */
public class DomainAllocateFlow implements TransactionalFlow {

  private static final String COLLISION_MESSAGE =
      "Domain on the name collision list was allocated. But by policy, the domain will not be "
      + "delegated. Please visit https://www.icann.org/namecollision  for more information on name "
      + "collision.";

  @Inject ExtensionManager extensionManager;
  @Inject AuthInfo authInfo;
  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject EppInput eppInput;
  @Inject Lazy<DnsQueue> dnsQueue;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainAllocateFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        FeeCreateCommandExtension.class,
        SecDnsCreateExtension.class,
        MetadataExtension.class,
        AllocateCreateExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    verifyIsSuperuser();
    DateTime now = ofy().getTransactionTime();
    Create command = cloneAndLinkReferences((Create) resourceCommand, now);
    failfastForCreate(targetId, now);
    verifyResourceDoesNotExist(DomainResource.class, targetId, now);
    InternetDomainName domainName = validateDomainName(command.getFullyQualifiedDomainName());
    Registry registry = Registry.get(domainName.parent().toString());
    Period period = command.getPeriod();
    Integer years = period.getValue();
    verifyUnitIsYears(period);
    validateCreateCommandContactsAndNameservers(command, registry.getTldStr());
    SecDnsCreateExtension secDnsCreate =
        validateSecDnsExtension(eppInput.getSingleExtension(SecDnsCreateExtension.class));
    boolean isSunrushAddGracePeriod = isNullOrEmpty(command.getNameservers());
    AllocateCreateExtension allocateCreate =
        eppInput.getSingleExtension(AllocateCreateExtension.class);
    DomainApplication application =
        loadAndValidateApplication(allocateCreate.getApplicationRoid(), now);
    String repoId = createDomainRepoId(ObjectifyService.allocateId(), registry.getTldStr());
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    HistoryEntry historyEntry = buildHistory(repoId, period, now);
    entitiesToSave.add(historyEntry);
    ImmutableSet<? extends ImmutableObject> billsAndPolls = createBillingEventsAndPollMessages(
        domainName, application, historyEntry, isSunrushAddGracePeriod, registry, now, years);
    entitiesToSave.addAll(billsAndPolls);
    DateTime registrationExpirationTime = leapSafeAddYears(now, years);
    DomainResource newDomain = new DomainResource.Builder()
        .setCreationClientId(clientId)
        .setCurrentSponsorClientId(clientId)
        .setRepoId(repoId)
        .setIdnTableName(validateDomainNameWithIdnTables(domainName))
        .setRegistrationExpirationTime(registrationExpirationTime)
        .setAutorenewBillingEvent(Key.create(getOnly(billsAndPolls, BillingEvent.Recurring.class)))
        .setAutorenewPollMessage(Key.create(getOnly(billsAndPolls, PollMessage.Autorenew.class)))
        .setApplicationTime(allocateCreate.getApplicationTime())
        .setApplication(Key.create(application))
        .setSmdId(allocateCreate.getSmdId())
        .setLaunchNotice(allocateCreate.getNotice())
        .setDsData(secDnsCreate == null ? application.getDsData() : secDnsCreate.getDsData())
        .addGracePeriod(createGracePeriod(
            isSunrushAddGracePeriod, getOnly(billsAndPolls, BillingEvent.OneTime.class)))
        // Names on the collision list will not be delegated. Set server hold.
        .setStatusValues(ReservationType.NAME_COLLISION == getReservationType(domainName)
            ? ImmutableSet.of(StatusValue.SERVER_HOLD)
            : ImmutableSet.<StatusValue>of())
        .setRegistrant(command.getRegistrant())
        .setAuthInfo(command.getAuthInfo())
        .setFullyQualifiedDomainName(targetId)
        .setNameservers(command.getNameservers())
        .setContacts(command.getContacts())
        .build();
    entitiesToSave.add(
        newDomain,
        buildApplicationHistory(application, now),
        updateApplication(application),
        ForeignKeyIndex.create(newDomain, newDomain.getDeletionTime()),
        EppResourceIndex.create(Key.create(newDomain)));
    // Anchor tenant registrations override LRP.
    String authInfoToken = authInfo.getPw().getValue();
    if (hasLrpToken(domainName, registry, authInfoToken, now)) {
      entitiesToSave.add(prepareMarkedLrpTokenEntity(authInfoToken, domainName, historyEntry));
    }
    ofy().save().entities(entitiesToSave.build());
    enqueueTasks(allocateCreate, newDomain);
    return responseBuilder
        .setResData(DomainCreateData.create(targetId, now, registrationExpirationTime))
        .setExtensions(createResponseExtensions(now, registry, years))
        .build();
  }

  private <T extends ImmutableObject> T getOnly(
      Iterable<? extends ImmutableObject> objects, Class<T> clazz) {
    return getOnlyElement(filter(objects, clazz));
  }

  private void verifyIsSuperuser() throws OnlySuperuserCanAllocateException {
    if (!isSuperuser) {
      throw new OnlySuperuserCanAllocateException();
    }
  }

  private DomainApplication loadAndValidateApplication(
      String applicationRoid, DateTime now) throws EppException {
    DomainApplication application = loadDomainApplication(applicationRoid, now);
    if (application == null) {
      throw new MissingApplicationException(applicationRoid);
    }
    if (application.getApplicationStatus().isFinalStatus()) {
      throw new HasFinalStatusException();
    }
    return application;
  }

  private HistoryEntry buildHistory(String repoId, Period period, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_ALLOCATE)
        .setPeriod(period)
        .setModificationTime(now)
        .setParent(Key.create(DomainResource.class, repoId))
        .build();
  }

  private ImmutableSet<? extends ImmutableObject> createBillingEventsAndPollMessages(
      InternetDomainName domainName,
      DomainApplication application,
      HistoryEntry historyEntry,
      boolean isSunrushAddGracePeriod,
      Registry registry,
      DateTime now,
      int years) {
    DateTime registrationExpirationTime = leapSafeAddYears(now, years);
    BillingEvent.OneTime oneTimeBillingEvent = createOneTimeBillingEvent(
        application, historyEntry, isSunrushAddGracePeriod, registry, now, years);
    PollMessage.OneTime oneTimePollMessage =
        createOneTimePollMessage(application, historyEntry, getReservationType(domainName), now);
    // Create a new autorenew billing event and poll message starting at the expiration time.
    BillingEvent.Recurring autorenewBillingEvent =
        createAutorenewBillingEvent(historyEntry, registrationExpirationTime);
    PollMessage.Autorenew autorenewPollMessage =
        createAutorenewPollMessage(historyEntry, registrationExpirationTime);
    return ImmutableSet.of(
        oneTimePollMessage,
        oneTimeBillingEvent,
        autorenewBillingEvent,
        autorenewPollMessage);
  }

  private BillingEvent.OneTime createOneTimeBillingEvent(
      DomainApplication application,
      HistoryEntry historyEntry,
      boolean isSunrushAddGracePeriod,
      Registry registry,
      DateTime now,
      int years) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setFlags(ImmutableSet.of(
            Flag.ALLOCATION,
            application.getEncodedSignedMarks().isEmpty() ? Flag.LANDRUSH : Flag.SUNRISE))
        .setTargetId(targetId)
        .setClientId(clientId)
        // Note that the cost is calculated as of now, i.e. the event time, not the billing time,
        // which may be some additional days into the future.
        .setCost(getDomainCreateCost(targetId, now, years))
        .setPeriodYears(years)
        .setEventTime(now)
        // If there are no nameservers on the domain, then they get the benefit of the sunrush
        // add grace period, which is longer than the standard add grace period.
        .setBillingTime(now.plus(isSunrushAddGracePeriod
            ? registry.getSunrushAddGracePeriodLength()
            : registry.getAddGracePeriodLength()))
        .setParent(historyEntry)
        .build();
  }

  private BillingEvent.Recurring createAutorenewBillingEvent(
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

  private PollMessage.Autorenew createAutorenewPollMessage(
      HistoryEntry historyEntry, DateTime registrationExpirationTime) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setClientId(clientId)
        .setEventTime(registrationExpirationTime)
        .setMsg("Domain was auto-renewed.")
        .setParent(historyEntry)
        .build();
  }

  private static GracePeriod createGracePeriod(boolean isSunrushAddGracePeriod,
      BillingEvent.OneTime oneTimeBillingEvent) {
    return GracePeriod.forBillingEvent(
        isSunrushAddGracePeriod ? GracePeriodStatus.SUNRUSH_ADD : GracePeriodStatus.ADD,
        oneTimeBillingEvent);
  }

  /** Create a history entry (with no xml or trid) to record that we updated the application. */
  private static HistoryEntry buildApplicationHistory(DomainApplication application, DateTime now) {
    return new HistoryEntry.Builder()
        .setType(HistoryEntry.Type.DOMAIN_APPLICATION_STATUS_UPDATE)
        .setParent(application)
        .setModificationTime(now)
        .setClientId(application.getCurrentSponsorClientId())
        .setBySuperuser(true)
        .build();
  }

  /** Update the application itself. */
  private DomainApplication updateApplication(DomainApplication application) {
    return application.asBuilder()
        .setApplicationStatus(ApplicationStatus.ALLOCATED)
        .removeStatusValue(StatusValue.PENDING_CREATE)
        .build();
  }

  /** Create a poll message informing the registrar that the application status was updated. */
  private PollMessage.OneTime createOneTimePollMessage(
      DomainApplication application,
      HistoryEntry historyEntry,
      ReservationType reservationType,
      DateTime now) {
    return new PollMessage.OneTime.Builder()
        .setClientId(historyEntry.getClientId())
        .setEventTime(now)
        .setMsg(reservationType == ReservationType.NAME_COLLISION
            ? COLLISION_MESSAGE  // Remind the registrar of the name collision policy.
            : "Domain was allocated")
        .setResponseData(ImmutableList.of(
            DomainPendingActionNotificationResponse.create(
                targetId, true, application.getCreationTrid(), now)))
        .setResponseExtensions(ImmutableList.of(
            new LaunchInfoResponseExtension.Builder()
                .setApplicationId(application.getForeignKey())
                .setPhase(application.getPhase())
                .setApplicationStatus(ApplicationStatus.ALLOCATED)
                .build()))
        .setParent(historyEntry)
        .build();
  }

  private boolean hasLrpToken(
      InternetDomainName domainName, Registry registry, String authInfoToken, DateTime now) {
    return registry.getLrpPeriod().contains(now)
        && !matchesAnchorTenantReservation(domainName, authInfoToken);
  }

  private void enqueueTasks(AllocateCreateExtension allocateCreate, DomainResource newDomain) {
    if (newDomain.shouldPublishToDns()) {
      dnsQueue.get().addDomainRefreshTask(newDomain.getFullyQualifiedDomainName());
    }
    if (allocateCreate.getSmdId() != null || allocateCreate.getNotice() != null) {
      LordnTask.enqueueDomainResourceTask(newDomain);
    }
  }

  private ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      DateTime now, Registry registry, int years) throws EppException {
    FeesAndCredits feesAndCredits = pricingLogic.getCreatePrice(registry, targetId, now, years);
    FeeCreateCommandExtension feeCreate =
        eppInput.getSingleExtension(FeeCreateCommandExtension.class);
    return (feeCreate == null)
        ? ImmutableList.<FeeTransformResponseExtension>of()
        : ImmutableList.of(createFeeCreateResponse(feeCreate, feesAndCredits));
  }

  /** Domain application with specific ROID does not exist. */
  static class MissingApplicationException extends ObjectDoesNotExistException {
    public MissingApplicationException(String applicationRoid) {
      super(DomainApplication.class, applicationRoid);
    }
  }

  /** Domain application already has a final status. */
  static class HasFinalStatusException extends StatusProhibitsOperationException {
    public HasFinalStatusException() {
      super("Domain application already has a final status");
    }
  }

  /** Only a superuser can allocate domains. */
  static class OnlySuperuserCanAllocateException extends AuthorizationErrorException {
    public OnlySuperuserCanAllocateException() {
      super("Only a superuser can allocate domains");
    }
  }
}
