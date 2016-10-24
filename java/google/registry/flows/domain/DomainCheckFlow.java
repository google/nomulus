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

import static google.registry.flows.ResourceFlowUtils.verifyTargetIdCount;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.getReservationType;
import static google.registry.flows.domain.DomainFlowUtils.handleFeeRequest;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.EppResourceUtils.checkResourcesExist;
import static google.registry.model.domain.fee.Fee.FEE_CHECK_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.index.DomainApplicationIndex.loadActiveApplicationsByDomainName;
import static google.registry.model.registry.label.ReservationType.UNRESERVED;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import google.registry.config.ConfigModule.Config;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.LoggedInFlow;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainCommand.Check;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CheckData.DomainCheck;
import google.registry.model.eppoutput.CheckData.DomainCheckData;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservationType;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

/**
 * An EPP flow that checks whether a domain can be provisioned.
 *
 * <p>This flow also supports the EPP fee extension and can return pricing information.
 *
 * @error {@link google.registry.flows.exceptions.TooManyResourceChecksException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.DashesInThirdAndFourthException}
 * @error {@link DomainFlowUtils.DomainLabelTooLongException}
 * @error {@link DomainFlowUtils.EmptyDomainNamePartException}
 * @error {@link DomainFlowUtils.FeeChecksDontSupportPhasesException}
 * @error {@link DomainFlowUtils.InvalidIdnDomainLabelException}
 * @error {@link DomainFlowUtils.InvalidPunycodeException}
 * @error {@link DomainFlowUtils.LeadingDashException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RestoresAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.UnknownFeeCommandException}
 * @error {@link OnlyCheckedNamesCanBeFeeCheckedException}
 */
public final class DomainCheckFlow extends LoggedInFlow {

  /**
   * The TLD states during which we want to report a domain with pending applications as
   * unavailable.
   */
  private static final Set<TldState> PENDING_ALLOCATION_TLD_STATES =
      Sets.immutableEnumSet(TldState.GENERAL_AVAILABILITY, TldState.QUIET_PERIOD);

  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @Config("maxChecks") int maxChecks;
  @Inject Optional<ExtraDomainValidation> extraDomainValidation;
  @Inject DomainCheckFlow() {}

  @Override
  protected final void initLoggedInFlow() throws EppException {
    registerExtensions(LaunchCheckExtension.class);
    registerExtensions(FEE_CHECK_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER);
  }

  @Override
  public EppOutput run() throws EppException {
    List<String> targetIds = ((Check) resourceCommand).getTargetIds();
    verifyTargetIdCount(targetIds, maxChecks);
    ImmutableMap.Builder<String, InternetDomainName> domains = new ImmutableMap.Builder<>();
    // Only check that the registrar has access to a TLD the first time it is encountered
    Set<String> seenTlds = new HashSet<>();
    for (String targetId : ImmutableSet.copyOf(targetIds)) {
      InternetDomainName domainName = validateDomainName(targetId);
      validateDomainNameWithIdnTables(domainName);
      // This validation is moderately expensive, so cache the results.
      domains.put(targetId, domainName);
      String tld = domainName.parent().toString();
      if (seenTlds.add(tld)) {
        checkAllowedAccessToTld(getAllowedTlds(), tld);
        if (!isSuperuser) {
          verifyNotInPredelegation(Registry.get(tld), now);
        }
      }
    }
    ImmutableMap<String, InternetDomainName> domainNames = domains.build();
    Set<String> existingIds = checkResourcesExist(DomainResource.class, targetIds, now);
    ImmutableList.Builder<DomainCheck> checks = new ImmutableList.Builder<>();
    for (String targetId : targetIds) {
      String message = getMessageForCheck(domainNames.get(targetId), existingIds);
      checks.add(DomainCheck.create(message == null, targetId, message));
    }
    return createOutput(
        SUCCESS,
        DomainCheckData.create(checks.build()),
        getResponseExtensions(domainNames));
  }

  private String getMessageForCheck(
      InternetDomainName domainName, Set<String> existingIds) {
    if (existingIds.contains(domainName.toString())) {
      return "In use";
    }
    Registry registry = Registry.get(domainName.parent().toString());
    if (PENDING_ALLOCATION_TLD_STATES.contains(registry.getTldState(now))
        && FluentIterable.from(loadActiveApplicationsByDomainName(domainName.toString(), now))
            .anyMatch(new Predicate<DomainApplication>() {
              @Override
              public boolean apply(DomainApplication input) {
                return !input.getApplicationStatus().isFinalStatus();
              }})) {
      return "Pending allocation";
    }
    ReservationType reservationType = getReservationType(domainName);
    if (reservationType == UNRESERVED
        && isDomainPremium(domainName.toString(), now)
        && registry.getPremiumPriceAckRequired()
        && Collections.disjoint(
            nullToEmpty(sessionMetadata.getServiceExtensionUris()),
            FEE_EXTENSION_URIS)) {
      return "Premium names require EPP ext.";
    }
    if (extraDomainValidation.isPresent()) {
      String extraBlockValidationMessage = extraDomainValidation.get()
          .getExtraValidationBlockMessage(
              domainName.parts().get(0), domainName.parent().toString(), reservationType, now);
      if (extraBlockValidationMessage != null) {
        return extraBlockValidationMessage;
      }
    }
    return reservationType.getMessageForCheck();
  }


  /** Handle the fee check extension. */
  private ImmutableList<? extends ResponseExtension> getResponseExtensions(
      ImmutableMap<String, InternetDomainName> domainNames) throws EppException {
    FeeCheckCommandExtension<?, ?> feeCheck =
        eppInput.getFirstExtensionOfClasses(FEE_CHECK_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER);
    if (feeCheck == null) {
      return null;  // No fee checks were requested.
    }
    ImmutableList.Builder<FeeCheckResponseExtensionItem> responseItems =
        new ImmutableList.Builder<>();
    for (FeeCheckCommandExtensionItem feeCheckItem : feeCheck.getItems()) {
      for (String domainName : getDomainNamesToCheckForFee(feeCheckItem, domainNames.keySet())) {
        FeeCheckResponseExtensionItem.Builder builder = feeCheckItem.createResponseBuilder();
        handleFeeRequest(
            feeCheckItem,
            builder,
            domainNames.get(domainName),
            clientId,
            feeCheck.getCurrency(),
            now,
            eppInput);
        responseItems.add(builder.setDomainNameIfSupported(domainName).build());
      }
    }
    return ImmutableList.of(feeCheck.createResponse(responseItems.build()));
  }

  /**
   * Return the domains to be checked for a particular fee check item. Some versions of the fee
   * extension specify the domain name in the extension item, while others use the list of domain
   * names from the regular check domain availability list.
   */
  private Set<String> getDomainNamesToCheckForFee(
      FeeCheckCommandExtensionItem feeCheckItem, ImmutableSet<String> availabilityCheckDomains)
      throws OnlyCheckedNamesCanBeFeeCheckedException {
    if (feeCheckItem.isDomainNameSupported()) {
      String domainNameInExtension = feeCheckItem.getDomainName();
      if (!availabilityCheckDomains.contains(domainNameInExtension)) {
        // Although the fee extension explicitly says it's ok to fee check a domain name that you
        // aren't also availability checking, we forbid it. This makes the experience simpler and
        // also means we can assume any domain names in the fee checks have been validated.
        throw new OnlyCheckedNamesCanBeFeeCheckedException();
      }
      return ImmutableSet.of(domainNameInExtension);
    }
    // If this version of the fee extension is nameless, use the full list of domains.
    return availabilityCheckDomains;
  }

  /** By server policy, fee check names must be listed in the availability check. */
  static class OnlyCheckedNamesCanBeFeeCheckedException extends ParameterValuePolicyErrorException {
    OnlyCheckedNamesCanBeFeeCheckedException() {
      super("By server policy, fee check names must be listed in the availability check");
    }
  }
}
