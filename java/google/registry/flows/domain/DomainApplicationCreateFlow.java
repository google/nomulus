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

import static google.registry.flows.domain.DomainFlowUtils.DISALLOWED_TLD_STATES_FOR_LAUNCH_FLOWS;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.model.domain.fee.Fee.FEE_CREATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER;
import static google.registry.model.eppoutput.Result.Code.Success;
import static google.registry.model.index.DomainApplicationIndex.loadActiveApplicationsByDomainName;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetReference;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ObjectAlreadyExistsException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainApplication.Builder;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchCreateResponseExtension;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.CreateData.DomainCreateData;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.AbstractSignedMark;
import google.registry.model.smd.EncodedSignedMark;
import java.util.List;
import javax.inject.Inject;

/**
 * An EPP flow that creates a new application for a domain resource.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ResourceCreateFlow.ResourceAlreadyExistsException}
 * @error {@link google.registry.flows.ResourceFlow.BadCommandForRegistryPhaseException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link BaseDomainCreateFlow.AcceptedTooLongAgoException}
 * @error {@link BaseDomainCreateFlow.ClaimsPeriodEndedException}
 * @error {@link BaseDomainCreateFlow.ExpiredClaimException}
 * @error {@link BaseDomainCreateFlow.InvalidTcnIdChecksumException}
 * @error {@link BaseDomainCreateFlow.InvalidTrademarkValidatorException}
 * @error {@link BaseDomainCreateFlow.MalformedTcnIdException}
 * @error {@link BaseDomainCreateFlow.MaxSigLifeNotSupportedException}
 * @error {@link BaseDomainCreateFlow.MissingClaimsNoticeException}
 * @error {@link BaseDomainCreateFlow.UnexpectedClaimsNoticeException}
 * @error {@link BaseDomainCreateFlow.UnsupportedMarkTypeException}
 * @error {@link DomainApplicationCreateFlow.LandrushApplicationDisallowedDuringSunriseException}
 * @error {@link DomainApplicationCreateFlow.NoticeCannotBeUsedWithSignedMarkException}
 * @error {@link DomainApplicationCreateFlow.SunriseApplicationDisallowedDuringLandrushException}
 * @error {@link DomainApplicationCreateFlow.UncontestedSunriseApplicationBlockedInLandrushException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.Base64RequiredForEncodedSignedMarksException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.LaunchPhaseMismatchException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedException}
 * @error {@link DomainFlowUtils.NoMarksFoundMatchingDomainException}
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
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 */
public class DomainApplicationCreateFlow extends BaseDomainCreateFlow<DomainApplication, Builder> {

  @Inject DomainApplicationCreateFlow() {}

  @Override
  protected void initDomainCreateFlow() {
    registerExtensions(LaunchCreateExtension.class);
    registerExtensions(FEE_CREATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER);
  }

  @Override
  protected void validateDomainLaunchCreateExtension() throws EppException {
    if (launchCreate.getSignedMarks().isEmpty()) {
      // During sunrise, a signed mark is required since only trademark holders are allowed to
      // create an application. However, we found no marks (ie, this was a landrush application).
      if (tldState == TldState.SUNRISE) {
        throw new LandrushApplicationDisallowedDuringSunriseException();
      }
    } else {
      if (launchCreate.getNotice() != null) {  // Can't use a claims notice id with a signed mark.
        throw new NoticeCannotBeUsedWithSignedMarkException();
      }
      if (tldState == TldState.LANDRUSH) {
        throw new SunriseApplicationDisallowedDuringLandrushException();
      }
    }
  }

  @Override
  protected void verifyDomainCreateIsAllowed() throws EppException {
    validateFeeChallenge(
        targetId, getTld(), now, feeCreate, commandOperations.getTotalCost());
    if (tldState == TldState.LANDRUSH && !isSuperuser) {
      // Prohibit creating a landrush application in LANDRUSH (but not in SUNRUSH) if there is
      // exactly one sunrise application for the same name.
      List<DomainApplication> applications = FluentIterable
          .from(loadActiveApplicationsByDomainName(targetId, now))
          .limit(2)
          .toList();
      if (applications.size() == 1 && applications.get(0).getPhase().equals(LaunchPhase.SUNRISE)) {
        throw new UncontestedSunriseApplicationBlockedInLandrushException();
      }
    }
    // Fail if the domain is already registered (e.g. this is a landrush application but the domain
    // was awarded at the end of sunrise).
    if (loadAndGetReference(DomainResource.class, targetId, now) != null) {
      throw new ResourceAlreadyExistsException(targetId);
    }
  }

  @Override
  protected void setDomainCreateProperties(Builder builder) {
    builder
        .setCreationTrid(trid)
        .setPhase(launchCreate.getPhase())
        .setApplicationStatus(ApplicationStatus.VALIDATED)
        .addStatusValue(StatusValue.PENDING_CREATE);
    if (!launchCreate.getSignedMarks().isEmpty()) {
      builder.setEncodedSignedMarks(FluentIterable
          .from(launchCreate.getSignedMarks())
          .transform(new Function<AbstractSignedMark, EncodedSignedMark>() {
            @Override
            public EncodedSignedMark apply(AbstractSignedMark abstractSignedMark) {
              // We verified that this is the case in verifyDomainCreateIsAllowed().
              return (EncodedSignedMark) abstractSignedMark;
            }})
          .toList());
    }
  }

  @Override
  protected final ImmutableSet<TldState> getDisallowedTldStates() {
    return DISALLOWED_TLD_STATES_FOR_LAUNCH_FLOWS;
  }

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.DOMAIN_APPLICATION_CREATE;
  }

  @Override
  protected final Period getCommandPeriod() {
    return command.getPeriod();
  }

  @Override
  protected boolean tryToLoadExisting() {
    // Multiple domain applications can be created for the same targetId (which is the fully
    // qualified domain name), so don't try to load an existing resource with the same target id.
    return false;
  }

  @Override
  protected final EppOutput getOutput() {
    ImmutableList.Builder<ResponseExtension> responseExtensionsBuilder =
        new ImmutableList.Builder<>();
    responseExtensionsBuilder.add(new LaunchCreateResponseExtension.Builder()
        .setPhase(launchCreate.getPhase())
        .setApplicationId(newResource.getForeignKey())
        .build());
    if (feeCreate != null) {
      responseExtensionsBuilder.add(feeCreate.createResponseBuilder()
          .setCurrency(commandOperations.getCurrency())
          .setFees(commandOperations.getFees())
          .build());
    }

    return createOutput(
        Success,
        DomainCreateData.create(newResource.getFullyQualifiedDomainName(), now, null),
        responseExtensionsBuilder.build());
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
