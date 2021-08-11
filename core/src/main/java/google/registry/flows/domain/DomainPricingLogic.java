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

import static google.registry.flows.domain.DomainFlowUtils.zeroInCurrency;
import static google.registry.pricing.PricingEngineProxy.getPricesForDomainName;

import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.FlowScope;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.custom.DomainPricingCustomLogic.CreatePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RenewPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RestorePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.TransferPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.UpdatePriceParameters;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.model.tld.Registry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Provides pricing for create, renew, etc, operations, with call-outs that can be customized by
 * providing a {@link DomainPricingCustomLogic} implementation that operates on cross-TLD or per-TLD
 * logic.
 */
@FlowScope
public final class DomainPricingLogic {

  @Inject DomainPricingCustomLogic customLogic;

  @Inject
  DomainPricingLogic() {}

  /**
   * Returns a new create price for the pricer.
   *
   * <p>If {@code allocationToken} is present and the domain is non-premium, that discount will be
   * applied to the first year.
   */
  FeesAndCredits getCreatePrice(
      Registry registry,
      String domainName,
      DateTime dateTime,
      int years,
      boolean isAnchorTenant,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();

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
    Fee eapFee = registry.getEapFeeFor(dateTime);
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
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .setYears(years)
            .build());
  }

  /** Returns a new renew price for the pricer. */
  @SuppressWarnings("unused")
  FeesAndCredits getRenewPrice(Registry registry, String domainName, DateTime dateTime, int years)
      throws EppException {
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    BigDecimal renewCost = domainPrices.getRenewCost().multipliedBy(years).getAmount();
    return customLogic.customizeRenewPrice(
        RenewPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(registry.getCurrency())
                    .addFeeOrCredit(Fee.create(renewCost, FeeType.RENEW, domainPrices.isPremium()))
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .setYears(years)
            .build());
  }

  /** Returns a new restore price for the pricer. */
  FeesAndCredits getRestorePrice(
      Registry registry, String domainName, DateTime dateTime, boolean isExpired)
      throws EppException {
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    FeesAndCredits.Builder feesAndCredits =
        new FeesAndCredits.Builder()
            .setCurrency(registry.getCurrency())
            .addFeeOrCredit(
                Fee.create(registry.getStandardRestoreCost().getAmount(), FeeType.RESTORE, false));
    if (isExpired) {
      feesAndCredits.addFeeOrCredit(
          Fee.create(
              domainPrices.getRenewCost().getAmount(), FeeType.RENEW, domainPrices.isPremium()));
    }
    return customLogic.customizeRestorePrice(
        RestorePriceParameters.newBuilder()
            .setFeesAndCredits(feesAndCredits.build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns a new transfer price for the pricer. */
  FeesAndCredits getTransferPrice(Registry registry, String domainName, DateTime dateTime)
      throws EppException {
    DomainPrices domainPrices = getPricesForDomainName(domainName, dateTime);
    return customLogic.customizeTransferPrice(
        TransferPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(registry.getCurrency())
                    .addFeeOrCredit(
                        Fee.create(
                            domainPrices.getRenewCost().getAmount(),
                            FeeType.RENEW,
                            domainPrices.isPremium()))
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns a new update price for the pricer. */
  FeesAndCredits getUpdatePrice(Registry registry, String domainName, DateTime dateTime)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();
    BaseFee feeOrCredit = Fee.create(zeroInCurrency(currency), FeeType.UPDATE, false);
    return customLogic.customizeUpdatePrice(
        UpdatePriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(currency)
                    .setFeesAndCredits(feeOrCredit)
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(dateTime)
            .build());
  }

  /** Returns the domain create cost with allocation-token-related discounts applied. */
  private Money getDomainCreateCostWithDiscount(
      DomainPrices domainPrices, int years, Optional<AllocationToken> allocationToken)
      throws EppException {
    if (allocationToken.isPresent()
        && allocationToken.get().getDiscountFraction() != 0.0
        && domainPrices.isPremium()
        && !allocationToken.get().shouldDiscountPremiums()) {
      throw new AllocationTokenInvalidForPremiumNameException();
    }
    Money oneYearCreateCost = domainPrices.getCreateCost();
    Money totalDomainCreateCost = oneYearCreateCost.multipliedBy(years);

    // Apply the allocation token discount, if applicable.
    if (allocationToken.isPresent()) {
      int discountedYears = Math.min(years, allocationToken.get().getDiscountYears());
      Money discount =
          oneYearCreateCost.multipliedBy(
              discountedYears * allocationToken.get().getDiscountFraction(),
              RoundingMode.HALF_EVEN);
      totalDomainCreateCost = totalDomainCreateCost.minus(discount);
    }
    return totalDomainCreateCost;
  }

  /** An allocation token was provided that is invalid for premium domains. */
  public static class AllocationTokenInvalidForPremiumNameException
      extends CommandUseErrorException {
    AllocationTokenInvalidForPremiumNameException() {
      super("A nonzero discount code cannot be applied to premium domains");
    }
  }
}
