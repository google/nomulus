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
import static google.registry.flows.domain.DomainFlowUtils.zeroInCurrency;
import static google.registry.flows.domain.token.AllocationTokenFlowUtils.validateTokenForPossiblePremiumName;
import static google.registry.pricing.PricingEngineProxy.getPricesForDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.custom.DomainPricingCustomLogic.CreatePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RenewPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RestorePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.TransferPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.UpdatePriceParameters;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenBehavior;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.model.tld.Tld;
import jakarta.inject.Inject;
import java.math.RoundingMode;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Provides pricing for create, renew, etc, operations, with call-outs that can be customized by
 * providing a {@link DomainPricingCustomLogic} implementation that operates on cross-TLD or per-TLD
 * logic.
 */
public final class DomainPricingLogic {

  private final DomainPricingCustomLogic customLogic;

  @Inject
  public DomainPricingLogic(DomainPricingCustomLogic customLogic) {
    this.customLogic = customLogic;
  }

  /**
   * Returns a new create price for the pricer.
   *
   * <p>If {@code allocationToken} is present and the domain is non-premium, that discount will be
   * applied to the first year.
   */
  FeesAndCredits getCreatePrice(
      Tld tld,
      String domainName,
      DateTime dateTime,
      int years,
      boolean isAnchorTenant,
      boolean isSunriseCreate,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    CurrencyUnit currency = tld.getCurrency();

    BaseFee createFee;
    // Domain create cost is always zero for anchor tenants
    if (isAnchorTenant) {
      createFee = Fee.create(zeroInCurrency(currency), FeeType.CREATE, false);
    } else {
      DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
      if (allocationToken.isPresent()) {
        // Handle any special NONPREMIUM / SPECIFIED cases configured in the token
        domainPrices =
            applyTokenToDomainPrices(domainPrices, tld, dateTime, years, allocationToken.get());
      }
      Money domainCreateCost =
          getDomainCreateCostWithDiscount(domainPrices, years, allocationToken, tld);
      // Apply a sunrise discount if configured and applicable
      if (isSunriseCreate) {
        domainCreateCost =
            domainCreateCost.multipliedBy(
                1.0d - RegistryConfig.getSunriseDomainCreateDiscount(), RoundingMode.HALF_EVEN);
      }
      createFee =
          Fee.create(domainCreateCost.getAmount(), FeeType.CREATE, domainPrices.isPremium());
    }

    // Create fees for the cost and the EAP fee, if any.
    Fee eapFee = tld.getEapFeeFor(dateTime);
    FeesAndCredits.Builder feesBuilder =
        new FeesAndCredits.Builder().setCurrency(currency).addFeeOrCredit(createFee);
    // Don't charge anchor tenants EAP fees.
    if (!isAnchorTenant && !eapFee.hasZeroCost()) {
      feesBuilder.addFeeOrCredit(eapFee);
    }

    // Apply custom logic to the create fee, if any.
    return customLogic.customizeCreatePrice(
        CreatePriceParameters.newBuilder()
            .setFeesAndCredits(feesBuilder.build())
            .setTld(tld)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .setYears(years)
            .build());
  }

  /** Returns a new renewal cost for the pricer. */
  public FeesAndCredits getRenewPrice(
      Tld tld,
      String domainName,
      DateTime dateTime,
      int years,
      @Nullable BillingRecurrence billingRecurrence,
      Optional<AllocationToken> allocationToken)
      throws AllocationTokenInvalidForCurrencyException,
          AllocationTokenInvalidForPremiumNameException {
    checkArgument(years > 0, "Number of years must be positive");
    Money renewCost;
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    boolean isRenewCostPremiumPrice;
    // recurrence is null if the domain is still available. Billing events are created
    // in the process of domain creation.
    if (billingRecurrence == null) {
      renewCost =
          getDomainRenewCostWithDiscount(tld, domainPrices, dateTime, years, allocationToken);
      isRenewCostPremiumPrice = domainPrices.isPremium();
    } else {
      switch (billingRecurrence.getRenewalPriceBehavior()) {
        case DEFAULT -> {
          renewCost =
              getDomainRenewCostWithDiscount(tld, domainPrices, dateTime, years, allocationToken);
          isRenewCostPremiumPrice = domainPrices.isPremium();
        }
          // if the renewal price behavior is specified, then the renewal price should be the same
          // as the creation price, which is stored in the billing event as the renewal price
        case SPECIFIED -> {
          checkArgumentPresent(
              billingRecurrence.getRenewalPrice(),
              "Unexpected behavior: renewal price cannot be null when renewal behavior is"
                  + " SPECIFIED");
          // Don't apply allocation token to renewal price when SPECIFIED
          renewCost = billingRecurrence.getRenewalPrice().get().multipliedBy(years);
          isRenewCostPremiumPrice = false;
        }
          // if the renewal price behavior is nonpremium, it means that the domain should be renewed
          // at standard price of domains at the time, even if the domain is premium
        case NONPREMIUM -> {
          renewCost =
              getDomainCostWithDiscount(
                  false,
                  years,
                  allocationToken,
                  tld.getStandardRenewCost(dateTime),
                  Optional.empty(),
                  tld);
          isRenewCostPremiumPrice = false;
        }
        default ->
            throw new IllegalArgumentException(
                String.format(
                    "Unknown RenewalPriceBehavior enum value: %s",
                    billingRecurrence.getRenewalPriceBehavior()));
      }
    }
    return customLogic.customizeRenewPrice(
        RenewPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(renewCost.getCurrencyUnit())
                    .addFeeOrCredit(
                        Fee.create(renewCost.getAmount(), FeeType.RENEW, isRenewCostPremiumPrice))
                    .build())
            .setTld(tld)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .setYears(years)
            .build());
  }

  /** Returns a new restore price for the pricer. */
  FeesAndCredits getRestorePrice(Tld tld, String domainName, DateTime dateTime, boolean isExpired)
      throws EppException {
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    FeesAndCredits.Builder feesAndCredits =
        new FeesAndCredits.Builder()
            .setCurrency(tld.getCurrency())
            .addFeeOrCredit(
                Fee.create(tld.getRestoreBillingCost().getAmount(), FeeType.RESTORE, false));
    if (isExpired) {
      feesAndCredits.addFeeOrCredit(
          Fee.create(
              domainPrices.getRenewCost().getAmount(), FeeType.RENEW, domainPrices.isPremium()));
    }
    return customLogic.customizeRestorePrice(
        RestorePriceParameters.newBuilder()
            .setFeesAndCredits(feesAndCredits.build())
            .setTld(tld)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns a new transfer price for the pricer. */
  FeesAndCredits getTransferPrice(
      Tld tld, String domainName, DateTime dateTime, @Nullable BillingRecurrence billingRecurrence)
      throws EppException {
    FeesAndCredits renewPrice =
        getRenewPrice(tld, domainName, dateTime, 1, billingRecurrence, Optional.empty());
    return customLogic.customizeTransferPrice(
        TransferPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(tld.getCurrency())
                    .addFeeOrCredit(
                        Fee.create(
                            renewPrice.getRenewCost().getAmount(),
                            FeeType.RENEW,
                            renewPrice.hasAnyPremiumFees()))
                    .build())
            .setTld(tld)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns a new update price for the pricer. */
  FeesAndCredits getUpdatePrice(Tld tld, String domainName, DateTime dateTime) throws EppException {
    CurrencyUnit currency = tld.getCurrency();
    BaseFee feeOrCredit = Fee.create(zeroInCurrency(currency), FeeType.UPDATE, false);
    return customLogic.customizeUpdatePrice(
        UpdatePriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(currency)
                    .setFeesAndCredits(feeOrCredit)
                    .build())
            .setTld(tld)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns the domain create cost with allocation-token-related discounts applied. */
  private Money getDomainCreateCostWithDiscount(
      DomainPrices domainPrices, int years, Optional<AllocationToken> allocationToken, Tld tld)
      throws EppException {
    return getDomainCostWithDiscount(
        domainPrices.isPremium(),
        years,
        allocationToken,
        domainPrices.getCreateCost(),
        Optional.of(domainPrices.getRenewCost()),
        tld);
  }

  /** Returns the domain renew cost with allocation-token-related discounts applied. */
  private Money getDomainRenewCostWithDiscount(
      Tld tld,
      DomainPrices domainPrices,
      DateTime dateTime,
      int years,
      Optional<AllocationToken> allocationToken)
      throws AllocationTokenInvalidForCurrencyException,
          AllocationTokenInvalidForPremiumNameException {
    // Short-circuit if the user sent an anchor-tenant or otherwise NONPREMIUM-renewal token
    if (allocationToken.isPresent()) {
      AllocationToken token = allocationToken.get();
      if (token.getRegistrationBehavior().equals(RegistrationBehavior.ANCHOR_TENANT)
          || token.getRenewalPriceBehavior().equals(RenewalPriceBehavior.NONPREMIUM)) {
        return tld.getStandardRenewCost(dateTime).multipliedBy(years);
      }
      if (token.getRenewalPriceBehavior().equals(RenewalPriceBehavior.SPECIFIED)) {
        return token.getRenewalPrice().get();
      }
    }
    return getDomainCostWithDiscount(
        domainPrices.isPremium(),
        years,
        allocationToken,
        domainPrices.getRenewCost(),
        Optional.empty(),
        tld);
  }

  /**
   * Returns the domain creation or renewal cost for the given number of {@code years}.
   *
   * <p>For domain creation, {@code firstYearCost} is the creation cost while {@code
   * subsequentYearCost} is the single-year renewal cost (which is guaranteed to be present).
   *
   * <p>For domain renewal, {@code firstYearCost} is the single-year renewal cost and {@code
   * subsequentYearCost} should be empty.
   */
  private Money getDomainCostWithDiscount(
      boolean isPremium,
      int years,
      Optional<AllocationToken> allocationToken,
      Money firstYearCost,
      Optional<Money> subsequentYearCost,
      Tld tld)
      throws AllocationTokenInvalidForCurrencyException,
          AllocationTokenInvalidForPremiumNameException {
    checkArgument(years > 0, "Registration years to get cost for must be positive.");
    validateTokenForPossiblePremiumName(allocationToken, isPremium);
    Money totalDomainFlowCost =
        firstYearCost.plus(subsequentYearCost.orElse(firstYearCost).multipliedBy(years - 1));

    // Apply the allocation token discount, if applicable.
    if (allocationToken.isPresent()
        && allocationToken.get().getTokenBehavior().equals(TokenBehavior.DEFAULT)) {
      if (allocationToken.get().getDiscountPrice().isPresent()) {
        if (!tld.getCurrency()
            .equals(allocationToken.get().getDiscountPrice().get().getCurrencyUnit())) {
          throw new AllocationTokenInvalidForCurrencyException();
        }

        int nonDiscountedYears = Math.max(0, years - allocationToken.get().getDiscountYears());
        totalDomainFlowCost =
            allocationToken
                .get()
                .getDiscountPrice()
                .get()
                .multipliedBy(allocationToken.get().getDiscountYears())
                .plus(subsequentYearCost.orElse(firstYearCost).multipliedBy(nonDiscountedYears));
      } else {
        // Assumes token has discount fraction set.
        int discountedYears = Math.min(years, allocationToken.get().getDiscountYears());
        if (discountedYears > 0) {
          var discount =
              firstYearCost
                  .plus(subsequentYearCost.orElse(firstYearCost).multipliedBy(discountedYears - 1))
                  .multipliedBy(
                      allocationToken.get().getDiscountFraction(), RoundingMode.HALF_EVEN);
          totalDomainFlowCost = totalDomainFlowCost.minus(discount);
        }
      }
    }
    return totalDomainFlowCost;
  }

  private DomainPrices applyTokenToDomainPrices(
      DomainPrices domainPrices, Tld tld, DateTime dateTime, int years, AllocationToken token) {
    // Convert to nonpremium iff no premium charges are included (either in create or any renewal)
    boolean convertToNonPremium =
        token.getRegistrationBehavior().equals(RegistrationBehavior.NONPREMIUM_CREATE)
            && (years == 1
                || !token.getRenewalPriceBehavior().equals(RenewalPriceBehavior.DEFAULT));
    boolean isPremium = domainPrices.isPremium() && !convertToNonPremium;
    Money createCost =
        token.getRegistrationBehavior().equals(RegistrationBehavior.NONPREMIUM_CREATE)
            ? tld.getCreateBillingCost(dateTime)
            : domainPrices.getCreateCost();
    Money renewCost =
        token.getRenewalPriceBehavior().equals(RenewalPriceBehavior.NONPREMIUM)
            ? tld.getStandardRenewCost(dateTime)
            : token.getRenewalPriceBehavior().equals(RenewalPriceBehavior.SPECIFIED)
                ? token.getRenewalPrice().get()
                : domainPrices.getRenewCost();
    return DomainPrices.create(isPremium, createCost, renewCost);
  }

  /** An allocation token was provided that is invalid for premium domains. */
  public static class AllocationTokenInvalidForPremiumNameException
      extends CommandUseErrorException {
    public AllocationTokenInvalidForPremiumNameException() {
      super("Token not valid for premium name");
    }
  }

  public static class AllocationTokenInvalidForCurrencyException extends CommandUseErrorException {
    public AllocationTokenInvalidForCurrencyException() {
      super("Token and domain currencies do not match.");
    }
  }
}
