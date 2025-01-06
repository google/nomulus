// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.persistence.BsaLabelUtils.getBlockedLabels;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyTargetIdCount;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.getReservationTypes;
import static google.registry.flows.domain.DomainFlowUtils.handleFeeRequest;
import static google.registry.flows.domain.DomainFlowUtils.isAnchorTenant;
import static google.registry.flows.domain.DomainFlowUtils.isRegisterBsaCreate;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.flows.domain.DomainFlowUtils.isValidReservedCreate;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.model.tld.label.ReservationType.getTypeOfHighestSeverity;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainCheckFlowCustomLogic;
import google.registry.flows.custom.DomainCheckFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainCheckFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForPremiumNameException;
import google.registry.flows.domain.token.AllocationTokenDomainCheckResults;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForCommandException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException;
import google.registry.model.EppResource;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Check;
import google.registry.model.domain.fee.FeeCheckCommandExtension;
import google.registry.model.domain.fee.FeeCheckCommandExtensionItem;
import google.registry.model.domain.fee.FeeCheckResponseExtensionItem;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CheckData.DomainCheck;
import google.registry.model.eppoutput.CheckData.DomainCheckData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.label.ReservationType;
import google.registry.persistence.VKey;
import google.registry.pricing.PricingEngineProxy;
import google.registry.util.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that checks whether a domain can be provisioned.
 *
 * <p>This flow also supports the EPP fee extension and can return pricing information.
 *
 * @error {@link google.registry.flows.exceptions.TooManyResourceChecksException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link DomainFlowUtils.BadDomainNameCharacterException}
 * @error {@link DomainFlowUtils.BadDomainNamePartsCountException}
 * @error {@link DomainFlowUtils.DomainNameExistsAsTldException}
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
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RestoresAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainFlowUtils.TrailingDashException}
 * @error {@link DomainFlowUtils.TransfersAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.UnknownFeeCommandException}
 * @error {@link OnlyCheckedNamesCanBeFeeCheckedException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_CHECK)
public final class DomainCheckFlow implements TransactionalFlow {

  private static final String STANDARD_FEE_RESPONSE_CLASS = "STANDARD";
  private static final String STANDARD_PROMOTION_FEE_RESPONSE_CLASS = "STANDARD PROMOTION";

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject @RegistrarId String registrarId;

  @Inject
  @Config("maxChecks")
  int maxChecks;

  @Inject @Superuser boolean isSuperuser;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject AllocationTokenFlowUtils allocationTokenFlowUtils;
  @Inject DomainCheckFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;

  @Inject
  DomainCheckFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeCheckCommandExtension.class, LaunchCheckExtension.class, AllocationTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    ImmutableList<String> domainNames = ((Check) resourceCommand).getTargetIds();
    verifyTargetIdCount(domainNames, maxChecks);
    DateTime now = clock.nowUtc();
    ImmutableMap.Builder<String, InternetDomainName> parsedDomainsBuilder =
        new ImmutableMap.Builder<>();
    // Only check that the registrar has access to a TLD the first time it is encountered
    Set<String> seenTlds = new HashSet<>();
    for (String domainName : ImmutableSet.copyOf(domainNames)) {
      InternetDomainName parsedDomain = validateDomainName(domainName);
      validateDomainNameWithIdnTables(parsedDomain);
      // This validation is moderately expensive, so cache the results.
      parsedDomainsBuilder.put(domainName, parsedDomain);
      String tld = parsedDomain.parent().toString();
      boolean tldFirstTimeSeen = seenTlds.add(tld);
      if (tldFirstTimeSeen && !isSuperuser) {
        checkAllowedAccessToTld(registrarId, tld);
        checkHasBillingAccount(registrarId, tld);
        verifyNotInPredelegation(Tld.get(tld), now);
      }
    }
    ImmutableMap<String, InternetDomainName> parsedDomains = parsedDomainsBuilder.build();
    flowCustomLogic.afterValidation(
        DomainCheckFlowCustomLogic.AfterValidationParameters.newBuilder()
            .setDomainNames(parsedDomains)
            // TODO: Use as of date from fee extension v0.12 instead of now, if specified.
            .setAsOfDate(now)
            .build());
    ImmutableMap<String, VKey<Domain>> existingDomains =
        ForeignKeyUtils.load(Domain.class, domainNames, now);
    // Check block labels only when there are unregistered domains, since "In use" goes before
    // "Blocked by BSA".
    ImmutableSet<InternetDomainName> bsaBlockedDomainNames =
        existingDomains.size() == parsedDomains.size()
            ? ImmutableSet.of()
            : getBsaBlockedDomains(parsedDomains.values(), now);
    Optional<AllocationTokenExtension> allocationTokenExtension =
        eppInput.getSingleExtension(AllocationTokenExtension.class);
    Optional<AllocationTokenDomainCheckResults> tokenDomainCheckResults =
        allocationTokenExtension.map(
            tokenExtension ->
                allocationTokenFlowUtils.checkDomainsWithToken(
                    ImmutableList.copyOf(parsedDomains.values()),
                    tokenExtension.getAllocationToken(),
                    registrarId,
                    now));

    ImmutableList.Builder<DomainCheck> checksBuilder = new ImmutableList.Builder<>();
    ImmutableSet.Builder<String> availableDomains = new ImmutableSet.Builder<>();
    ImmutableMap<String, TldState> tldStates =
        Maps.toMap(seenTlds, tld -> Tld.get(tld).getTldState(now));
    ImmutableMap<InternetDomainName, String> domainCheckResults =
        tokenDomainCheckResults
            .map(AllocationTokenDomainCheckResults::domainCheckResults)
            .orElse(ImmutableMap.of());
    Optional<AllocationToken> allocationToken =
        tokenDomainCheckResults.flatMap(AllocationTokenDomainCheckResults::token);
    for (String domainName : domainNames) {
      Optional<String> message =
          getMessageForCheck(
              parsedDomains.get(domainName),
              existingDomains,
              bsaBlockedDomainNames,
              domainCheckResults,
              tldStates,
              allocationToken);
      boolean isAvailable = message.isEmpty();
      checksBuilder.add(DomainCheck.create(isAvailable, domainName, message.orElse(null)));
      if (isAvailable) {
        availableDomains.add(domainName);
      }
    }
    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setDomainChecks(checksBuilder.build())
                .setResponseExtensions(
                    getResponseExtensions(
                        parsedDomains,
                        existingDomains,
                        availableDomains.build(),
                        now,
                        allocationToken))
                .setAsOfDate(now)
                .build());
    return responseBuilder
        .setResData(DomainCheckData.create(responseData.domainChecks()))
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private Optional<String> getMessageForCheck(
      InternetDomainName domainName,
      ImmutableMap<String, VKey<Domain>> existingDomains,
      ImmutableSet<InternetDomainName> bsaBlockedDomains,
      ImmutableMap<InternetDomainName, String> tokenCheckResults,
      ImmutableMap<String, TldState> tldStates,
      Optional<AllocationToken> allocationToken) {
    if (existingDomains.containsKey(domainName.toString())) {
      return Optional.of("In use");
    }
    TldState tldState = tldStates.get(domainName.parent().toString());
    if (isReserved(domainName, START_DATE_SUNRISE.equals(tldState))) {
      if (!isValidReservedCreate(domainName, allocationToken)
          && !isAnchorTenant(domainName, allocationToken, Optional.empty())) {
        ImmutableSet<ReservationType> reservationTypes = getReservationTypes(domainName);
        if (!reservationTypes.isEmpty()) {
          ReservationType highestSeverityType = getTypeOfHighestSeverity(reservationTypes);
          return Optional.of(highestSeverityType.getMessageForCheck());
        }
      }
    }
    Optional<String> tokenResult =
        Optional.ofNullable(emptyToNull(tokenCheckResults.get(domainName)));
    if (tokenResult.isPresent()) {
      return tokenResult;
    }
    if (isRegisterBsaCreate(domainName, allocationToken)
        || !bsaBlockedDomains.contains(domainName)) {
      return Optional.empty();
    }
    // TODO(weiminyu): extract to a constant for here and CheckApiAction.
    // Excerpt from BSA's custom message. Max len 32 chars by EPP XML schema.
    return Optional.of("Blocked by a GlobalBlock service");
  }

  /** Handle the fee check extension. */
  private ImmutableList<? extends ResponseExtension> getResponseExtensions(
      ImmutableMap<String, InternetDomainName> domainNames,
      ImmutableMap<String, VKey<Domain>> existingDomains,
      ImmutableSet<String> availableDomains,
      DateTime now,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    Optional<FeeCheckCommandExtension> feeCheckOpt =
        eppInput.getSingleExtension(FeeCheckCommandExtension.class);
    if (feeCheckOpt.isEmpty()) {
      return ImmutableList.of(); // No fee checks were requested.
    }
    FeeCheckCommandExtension<?, ?> feeCheck = feeCheckOpt.get();
    ImmutableList.Builder<FeeCheckResponseExtensionItem> responseItems =
        new ImmutableList.Builder<>();
    ImmutableMap<String, Domain> domainObjs =
        loadDomainsForChecks(feeCheck, domainNames, existingDomains);
    ImmutableMap<String, BillingRecurrence> recurrences = loadRecurrencesForDomains(domainObjs);

    boolean shouldUseTieredPricingPromotion =
        RegistryConfig.getTieredPricingPromotionRegistrarIds().contains(registrarId);
    for (FeeCheckCommandExtensionItem feeCheckItem : feeCheck.getItems()) {
      for (String domainName : getDomainNamesToCheckForFee(feeCheckItem, domainNames.keySet())) {
        Optional<AllocationToken> defaultToken =
            DomainFlowUtils.checkForDefaultToken(
                Tld.get(InternetDomainName.from(domainName).parent().toString()),
                domainName,
                feeCheckItem.getCommandName(),
                registrarId,
                now);
        FeeCheckResponseExtensionItem.Builder<?> builder = feeCheckItem.createResponseBuilder();
        Optional<Domain> domain = Optional.ofNullable(domainObjs.get(domainName));
        try {
          if (allocationToken.isPresent()) {
            AllocationTokenFlowUtils.validateToken(
                InternetDomainName.from(domainName),
                allocationToken.get(),
                feeCheckItem.getCommandName(),
                registrarId,
                PricingEngineProxy.isDomainPremium(domainName, now),
                now);
          }
          handleFeeRequest(
              feeCheckItem,
              builder,
              domainNames.get(domainName),
              domain,
              feeCheck.getCurrency(),
              now,
              pricingLogic,
              allocationToken.isPresent() ? allocationToken : defaultToken,
              availableDomains.contains(domainName),
              recurrences.getOrDefault(domainName, null));
          // In the case of a registrar that is running a tiered pricing promotion, we issue two
          // responses for the CREATE fee check command: one (the default response) with the
          // non-promotional price, and one (an extra STANDARD PROMO response) with the actual
          // promotional price.
          if (defaultToken.isPresent()
              && shouldUseTieredPricingPromotion
              && feeCheckItem
                  .getCommandName()
                  .equals(FeeQueryCommandExtensionItem.CommandName.CREATE)) {
            // First, set the promotional (real) price under the STANDARD PROMO class
            builder
                .setClass(STANDARD_PROMOTION_FEE_RESPONSE_CLASS)
                .setCommand(
                    FeeQueryCommandExtensionItem.CommandName.CUSTOM,
                    feeCheckItem.getPhase(),
                    feeCheckItem.getSubphase());

            // Next, get the non-promotional price and set it as the standard response to the CREATE
            // fee check command
            FeeCheckResponseExtensionItem.Builder<?> nonPromotionalBuilder =
                feeCheckItem.createResponseBuilder();
            handleFeeRequest(
                feeCheckItem,
                nonPromotionalBuilder,
                domainNames.get(domainName),
                domain,
                feeCheck.getCurrency(),
                now,
                pricingLogic,
                allocationToken,
                availableDomains.contains(domainName),
                recurrences.getOrDefault(domainName, null));
            responseItems.add(
                nonPromotionalBuilder
                    .setClass(STANDARD_FEE_RESPONSE_CLASS)
                    .setDomainNameIfSupported(domainName)
                    .build());
          }
          responseItems.add(builder.setDomainNameIfSupported(domainName).build());
        } catch (AllocationTokenInvalidForPremiumNameException
            | AllocationTokenNotValidForCommandException
            | AllocationTokenNotValidForDomainException
            | AllocationTokenNotValidForRegistrarException
            | AllocationTokenNotValidForTldException
            | AllocationTokenNotInPromotionException e) {
          // Allocation token is either not an active token or it is not valid for the EPP command,
          // registrar, domain, or TLD.
          Tld tld = Tld.get(InternetDomainName.from(domainName).parent().toString());
          responseItems.add(
              builder
                  .setDomainNameIfSupported(domainName)
                  .setPeriod(feeCheckItem.getPeriod())
                  .setCommand(
                      feeCheckItem.getCommandName(),
                      feeCheckItem.getPhase(),
                      feeCheckItem.getSubphase())
                  .setCurrencyIfSupported(tld.getCurrency())
                  .setClass("token-not-supported")
                  .build());
        }
      }
    }
    return ImmutableList.of(feeCheck.createResponse(responseItems.build()));
  }

  /**
   * Loads and returns all existing domains that are having restore/renew/transfer fees checked.
   *
   * <p>These need to be loaded for renews and transfers because there could be a relevant {@link
   * google.registry.model.billing.BillingBase.RenewalPriceBehavior} on the {@link
   * BillingRecurrence} affecting the price. They also need to be loaded for restores so that we can
   * check their expiration dates to determine if a one-year renewal is part of the cost of a
   * restore.
   *
   * <p>This may be resource-intensive for large checks of many restore fees, but those are
   * comparatively rare, and we are at least using an in-memory cache. Also, this will get a lot
   * nicer in Cloud SQL when we can SELECT just the fields we want rather than having to load the
   * entire entity.
   */
  private ImmutableMap<String, Domain> loadDomainsForChecks(
      FeeCheckCommandExtension<?, ?> feeCheck,
      ImmutableMap<String, InternetDomainName> domainNames,
      ImmutableMap<String, VKey<Domain>> existingDomains) {
    ImmutableList<String> restoreCheckDomains;
    if (feeCheck instanceof FeeCheckCommandExtensionV06) {
      // The V06 fee extension supports specifying the command fees to check on a per-domain basis.
      restoreCheckDomains =
          feeCheck.getItems().stream()
              .filter(fc -> fc.getCommandName().shouldLoadDomainForCheck())
              .map(FeeCheckCommandExtensionItem::getDomainName)
              .distinct()
              .collect(toImmutableList());
    } else if (feeCheck.getItems().stream()
        .anyMatch(fc -> fc.getCommandName().shouldLoadDomainForCheck())) {
      // The more recent fee extension versions support specifying the command fees to check only on
      // the overall domain check, not per-domain.
      restoreCheckDomains = ImmutableList.copyOf(domainNames.keySet());
    } else {
      // Fall-through case for more recent fee extension versions when the restore/renew/transfer
      // fees aren't being checked.
      restoreCheckDomains = ImmutableList.of();
    }

    // Filter down to just domains we know exist and then use the EppResource cache to load them.
    ImmutableMap<String, VKey<Domain>> existingDomainsToLoad =
        restoreCheckDomains.stream()
            .filter(existingDomains::containsKey)
            .collect(toImmutableMap(d -> d, existingDomains::get));
    ImmutableMap<VKey<? extends EppResource>, EppResource> loadedDomains =
        EppResource.loadCached(ImmutableList.copyOf(existingDomainsToLoad.values()));
    return ImmutableMap.copyOf(
        Maps.transformEntries(existingDomainsToLoad, (k, v) -> (Domain) loadedDomains.get(v)));
  }

  private ImmutableMap<String, BillingRecurrence> loadRecurrencesForDomains(
      ImmutableMap<String, Domain> domainObjs) {
    ImmutableMap<VKey<? extends BillingRecurrence>, BillingRecurrence> recurrences =
        tm().loadByKeys(
                domainObjs.values().stream()
                    .map(Domain::getAutorenewBillingEvent)
                    .collect(toImmutableSet()));
    return ImmutableMap.copyOf(
        Maps.transformValues(domainObjs, d -> recurrences.get(d.getAutorenewBillingEvent())));
  }

  /**
   * Return the domains to be checked for a particular fee check item. Some versions of the fee
   * extension specify the domain name in the extension item, while others use the list of domain
   * names from the regular check domain availability list.
   */
  private ImmutableSet<String> getDomainNamesToCheckForFee(
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

  static ImmutableSet<InternetDomainName> getBsaBlockedDomains(
      ImmutableCollection<InternetDomainName> parsedDomains, DateTime now) {
    Map<String, ImmutableList<InternetDomainName>> labelToDomainNames =
        parsedDomains.stream()
            .filter(
                parsedDomain -> isEnrolledWithBsa(Tld.get(parsedDomain.parent().toString()), now))
            .collect(
                Collectors.groupingBy(
                    parsedDomain -> parsedDomain.parts().get(0), toImmutableList()));
    ImmutableSet<String> blockedLabels =
        getBlockedLabels(ImmutableList.copyOf(labelToDomainNames.keySet()));
    labelToDomainNames.keySet().retainAll(blockedLabels);
    return labelToDomainNames.values().stream()
        .flatMap(Collection::stream)
        .collect(toImmutableSet());
  }

  /** By server policy, fee check names must be listed in the availability check. */
  static class OnlyCheckedNamesCanBeFeeCheckedException extends ParameterValuePolicyErrorException {
    OnlyCheckedNamesCanBeFeeCheckedException() {
      super("By server policy, fee check names must be listed in the availability check");
    }
  }
}
