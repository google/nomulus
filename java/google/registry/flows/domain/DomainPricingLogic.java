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

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainCreateCost;
import static google.registry.pricing.PricingEngineProxy.getDomainFeeClass;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.FlowScope;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.custom.DomainPricingCustomLogic.CreatePriceParameters;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.LrpTokenEntity;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.Fee;
import google.registry.model.eppinput.EppInput;
import google.registry.model.registry.Registry;
import java.util.List;
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

  /** A collection of fees and credits for a specific EPP transform. */
  public static final class FeesAndCredits extends ImmutableObject {
    private final CurrencyUnit currency;
    private final ImmutableList<Fee> fees;
    private final ImmutableList<Credit> credits;

    /** Constructs an FeesAndCredits object. The arguments are sorted into fees and credits. */
    public FeesAndCredits(CurrencyUnit currency, BaseFee... baseFees) {
      this.currency = checkArgumentNotNull(currency, "Currency may not be null in FeesAndCredits.");
      ImmutableList.Builder<Fee> feeBuilder = new ImmutableList.Builder<>();
      ImmutableList.Builder<Credit> creditBuilder = new ImmutableList.Builder<>();
      for (BaseFee feeOrCredit : baseFees) {
        if (feeOrCredit instanceof Credit) {
          creditBuilder.add((Credit) feeOrCredit);
        } else {
          feeBuilder.add((Fee) feeOrCredit);
        }
      }
      this.fees = feeBuilder.build();
      this.credits = creditBuilder.build();
    }

    private Money getTotalCostForType(FeeType type) {
      Money result = Money.zero(currency);
      checkArgumentNotNull(type);
      for (Fee fee : fees) {
        if (fee.getType() == type) {
          result = result.plus(fee.getCost());
        }
      }
      return result;
    }

    /** Returns the total cost of all fees and credits for the event. */
    public Money getTotalCost() {
      Money result = Money.zero(currency);
      for (Fee fee : fees) {
        result = result.plus(fee.getCost());
      }
      for (Credit credit : credits) {
        result = result.plus(credit.getCost());
      }
      return result;
    }

    /** Returns the create cost for the event. */
    public Money getCreateCost() {
      return getTotalCostForType(FeeType.CREATE);
    }

    /** Returns the EAP cost for the event. */
    public Money getEapCost() {
      return getTotalCostForType(FeeType.EAP);
    }

    /** Returns the list of fees for the event. */
    public ImmutableList<Fee> getFees() {
      return fees;
    }

    /** Returns the list of credits for the event. */
    public List<Credit> getCredits() {
      return nullToEmpty(credits);
    }

    /** Returns the currency for all fees in the event. */
    public final CurrencyUnit getCurrency() {
      return currency;
    }
  }

  /** Returns a new create price for the Pricer. */
  public FeesAndCredits getCreatePrice(
      Registry registry, String domainName, DateTime date, int years) throws EppException {
    CurrencyUnit currency = registry.getCurrency();

    // Get the vanilla create cost.
    BaseFee createFeeOrCredit =
        Fee.create(getDomainCreateCost(domainName, date, years).getAmount(), FeeType.CREATE);

    FeesAndCredits feesAndCredits;

    // Create fees for the cost and the EAP fee, if any.
    Fee eapFee = registry.getEapFeeFor(date);
    if (!eapFee.hasZeroCost()) {
      feesAndCredits = new FeesAndCredits(currency, createFeeOrCredit, eapFee);
    } else {
      feesAndCredits = new FeesAndCredits(currency, createFeeOrCredit);
    }

    // Apply custom logic to the create fee, if any.
    return customLogic.customizeCreatePrice(
        CreatePriceParameters.newBuilder()
            .setFeesAndCredits(feesAndCredits)
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .setYears(years)
            .build());

  }

  // TODO: (b/33000134) clean up the rest of the pricing calls.

  /**
   * Computes the renew fee or credit. This is called by other methods which use the renew fee
   * (renew, restore, etc).
   */
  static BaseFee getRenewFeeOrCredit(
      Registry registry,
      String domainName,
      String clientId,
      DateTime date,
      int years,
      EppInput eppInput)
      throws EppException {
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForTld(registry.getTldStr());
    if (extraFlowLogic.isPresent()) {
      // TODO: Consider changing the method definition to have the domain passed in to begin with.
      DomainResource domain = loadByForeignKey(DomainResource.class, domainName, date);
      if (domain == null) {
        throw new ResourceDoesNotExistException(DomainResource.class, domainName);
      }
      return extraFlowLogic.get().getRenewFeeOrCredit(domain, clientId, date, years, eppInput);
    } else {
      return Fee.create(getDomainRenewCost(domainName, date, years).getAmount(), FeeType.RENEW);
    }
  }

  /** Returns a new renew price for the pricer. */
  public static FeesAndCredits getRenewPrice(
      Registry registry,
      String domainName,
      String clientId,
      DateTime date,
      int years,
      EppInput eppInput)
      throws EppException {
    return new FeesAndCredits(
        registry.getCurrency(),
        getRenewFeeOrCredit(registry, domainName, clientId, date, years, eppInput));
  }

  /** Returns a new restore price for the pricer. */
  public static FeesAndCredits getRestorePrice(
      Registry registry, String domainName, String clientId, DateTime date, EppInput eppInput)
      throws EppException {
    return new FeesAndCredits(
        registry.getCurrency(),
        getRenewFeeOrCredit(registry, domainName, clientId, date, 1, eppInput),
        Fee.create(registry.getStandardRestoreCost().getAmount(), FeeType.RESTORE));
  }

  /** Returns a new transfer price for the pricer. */
  public static FeesAndCredits getTransferPrice(
      Registry registry,
      String domainName,
      String clientId,
      DateTime transferDate,
      int years,
      EppInput eppInput)
      throws EppException {
    // Currently, all transfer prices = renew prices, so just pass through.
    return getRenewPrice(registry, domainName, clientId, transferDate, years, eppInput);
  }

  /** Returns a new update price for the pricer. */
  public static FeesAndCredits getUpdatePrice(
      Registry registry, String domainName, String clientId, DateTime date, EppInput eppInput)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();

    // If there is extra flow logic, it may specify an update price. Otherwise, there is none.
    BaseFee feeOrCredit;
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForTld(registry.getTldStr());
    if (extraFlowLogic.isPresent()) {
      // TODO: Consider changing the method definition to have the domain passed in to begin with.
      DomainResource domain = loadByForeignKey(DomainResource.class, domainName, date);
      if (domain == null) {
        throw new ResourceDoesNotExistException(DomainResource.class, domainName);
      }
      feeOrCredit = extraFlowLogic.get().getUpdateFeeOrCredit(domain, clientId, date, eppInput);
    } else {
      feeOrCredit = Fee.create(Money.zero(registry.getCurrency()).getAmount(), FeeType.UPDATE);
    }

    return new FeesAndCredits(currency, feeOrCredit);
  }

  /** Returns a new domain application update price for the pricer. */
  public static FeesAndCredits getApplicationUpdatePrice(
      Registry registry,
      DomainApplication application,
      String clientId,
      DateTime date,
      EppInput eppInput)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();

    // If there is extra flow logic, it may specify an update price. Otherwise, there is none.
    BaseFee feeOrCredit;
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForTld(registry.getTldStr());
    if (extraFlowLogic.isPresent()) {
      feeOrCredit =
          extraFlowLogic
              .get()
              .getApplicationUpdateFeeOrCredit(application, clientId, date, eppInput);
    } else {
      feeOrCredit = Fee.create(Money.zero(registry.getCurrency()).getAmount(), FeeType.UPDATE);
    }

    return new FeesAndCredits(currency, feeOrCredit);
  }

  /** Returns the fee class for a given domain and date. */
  public static Optional<String> getFeeClass(String domainName, DateTime date) {
    return getDomainFeeClass(domainName, date);
  }

  /**
   * Checks whether an LRP token String maps to a valid {@link LrpTokenEntity} for the domain name's
   * TLD, and return that entity (wrapped in an {@link Optional}) if one exists.
   *
   * <p>This method has no knowledge of whether or not an auth code (interpreted here as an LRP
   * token) has already been checked against the reserved list for QLP (anchor tenant), as auth
   * codes are used for both types of registrations.
   */
  public static Optional<LrpTokenEntity> getMatchingLrpToken(
      String lrpToken, InternetDomainName domainName) {
    // Note that until the actual per-TLD logic is built out, what's being done here is a basic
    // domain-name-to-assignee match.
    if (!lrpToken.isEmpty()) {
      LrpTokenEntity token = ofy().load().key(Key.create(LrpTokenEntity.class, lrpToken)).now();
      if (token != null
          && token.getAssignee().equalsIgnoreCase(domainName.toString())
          && token.getRedemptionHistoryEntry() == null
          && token.getValidTlds().contains(domainName.parent().toString())) {
        return Optional.of(token);
      }
    }
    return Optional.<LrpTokenEntity>absent();
  }
}
