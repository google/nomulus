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
import static google.registry.pricing.PricingEngineProxy.getPricesForDomainName;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.custom.DomainPricingCustomLogic.CreatePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RenewPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RestorePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.TransferPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.UpdatePriceParameters;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenBehavior;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.model.tld.Tld;
import java.math.RoundingMode;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
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
      Optional<AllocationToken> allocationToken)
      throws EppException {
    CurrencyUnit currency = tld.getCurrency();

    BaseFee createFeeOrCredit;
    // Domain create cost is always zero for anchor tenants
    if (isAnchorTenant) {
      createFeeOrCredit = Fee.create(zeroInCurrency(currency), FeeType.CREATE, false);
    } else {
      DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
      Money domainCreateCost =
          getDomainCreateCostWithDiscount(domainPrices, years, allocationToken);
      createFeeOrCredit =
          Fee.create(domainCreateCost.getAmount(), FeeType.CREATE, domainPrices.isPremium());
    }

    // Create fees for the cost and the EAP fee, if any.
    Fee eapFee = tld.getEapFeeFor(dateTime);
    FeesAndCredits.Builder feesBuilder =
        new FeesAndCredits.Builder().setCurrency(currency).addFeeOrCredit(createFeeOrCredit);
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
      @Nullable Recurring recurringBillingEvent,
      Optional<AllocationToken> allocationToken)
      throws AllocationTokenInvalidForPremiumNameException {
    checkArgument(years > 0, "Number of years must be positive");
    Money renewCost;
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    boolean isRenewCostPremiumPrice;
    // recurring billing event is null if the domain is still available. Billing events are created
    // in the process of domain creation.
    if (recurringBillingEvent == null) {
      renewCost = getDomainRenewCostWithDiscount(domainPrices, years, allocationToken);
      isRenewCostPremiumPrice = domainPrices.isPremium();
    } else {
      switch (recurringBillingEvent.getRenewalPriceBehavior()) {
        case DEFAULT:
          renewCost = getDomainRenewCostWithDiscount(domainPrices, years, allocationToken);
          isRenewCostPremiumPrice = domainPrices.isPremium();
          break;
          // if the renewal price behavior is specified, then the renewal price should be the same
          // as the creation price, which is stored in the billing event as the renewal price
        case SPECIFIED:
          checkArgumentPresent(
              recurringBillingEvent.getRenewalPrice(),
              "Unexpected behavior: renewal price cannot be null when renewal behavior is"
                  + " SPECIFIED");
          // Don't apply allocation token to renewal price when SPECIFIED
          renewCost = recurringBillingEvent.getRenewalPrice().get().multipliedBy(years);
          isRenewCostPremiumPrice = false;
          break;
          // if the renewal price behavior is nonpremium, it means that the domain should be renewed
          // at standard price of domains at the time, even if the domain is premium
        case NONPREMIUM:
          renewCost =
              getDomainCostWithDiscount(
                  false,
                  years,
                  allocationToken,
                  Tld.get(getTldFromDomainName(domainName)).getStandardRenewCost(dateTime));
          isRenewCostPremiumPrice = false;
          break;
        default:
          throw new IllegalArgumentException(
              String.format(
                  "Unknown RenewalPriceBehavior enum value: %s",
                  recurringBillingEvent.getRenewalPriceBehavior()));
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
                Fee.create(tld.getStandardRestoreCost().getAmount(), FeeType.RESTORE, false));
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
      Tld tld, String domainName, DateTime dateTime, @Nullable Recurring recurringBillingEvent)
      throws EppException {
    FeesAndCredits renewPrice =
        getRenewPrice(tld, domainName, dateTime, 1, recurringBillingEvent, Optional.empty());
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
      DomainPrices domainPrices, int years, Optional<AllocationToken> allocationToken)
      throws EppException {
    return getDomainCostWithDiscount(
        domainPrices.isPremium(), years, allocationToken, domainPrices.getCreateCost());
  }

  /** Returns the domain renew cost with allocation-token-related discounts applied. */
  private Money getDomainRenewCostWithDiscount(
      DomainPrices domainPrices, int years, Optional<AllocationToken> allocationToken)
      throws AllocationTokenInvalidForPremiumNameException {
    return getDomainCostWithDiscount(
        domainPrices.isPremium(), years, allocationToken, domainPrices.getRenewCost());
  }

  private Money getDomainCostWithDiscount(
      boolean isPremium, int years, Optional<AllocationToken> allocationToken, Money oneYearCost)
      throws AllocationTokenInvalidForPremiumNameException {
    if (allocationToken.isPresent()
        && allocationToken.get().getDiscountFraction() != 0.0
        && isPremium
        && !allocationToken.get().shouldDiscountPremiums()) {
      throw new AllocationTokenInvalidForPremiumNameException();
    }
    Money totalDomainFlowCost = oneYearCost.multipliedBy(years);

    // Apply the allocation token discount, if applicable.
    if (allocationToken.isPresent()
        && allocationToken.get().getTokenBehavior().equals(TokenBehavior.DEFAULT)) {
      int discountedYears = Math.min(years, allocationToken.get().getDiscountYears());
      Money discount =
          oneYearCost.multipliedBy(
              discountedYears * allocationToken.get().getDiscountFraction(),
              RoundingMode.HALF_EVEN);
      totalDomainFlowCost = totalDomainFlowCost.minus(discount);
    }
    return totalDomainFlowCost;
  }

  /** An allocation token was provided that is invalid for premium domains. */
  public static class AllocationTokenInvalidForPremiumNameException
      extends CommandUseErrorException {
    AllocationTokenInvalidForPremiumNameException() {
      super("A nonzero discount code cannot be applied to premium domains");
    }
  }
}
