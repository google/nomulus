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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.flows.domain.DomainFlowUtils.zeroInCurrency;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.Fee;
import java.math.BigDecimal;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/** A collection of fees and credits for a specific EPP transform. */
public class FeesAndCredits extends ImmutableObject implements Buildable {

  private CurrencyUnit currency;
  private boolean feeExtensionRequired;
  private ImmutableList<Fee> fees;
  private ImmutableList<Credit> credits;

  private Money getTotalCostForType(FeeType type) {
    checkArgumentNotNull(type);
    return Money.of(
        currency,
        fees.stream()
            .filter(f -> f.getType() == type)
            .map(BaseFee::getCost)
            .reduce(zeroInCurrency(currency), BigDecimal::add));
  }

  public boolean hasPremiumFeesOfType(FeeType type) {
    return fees.stream().filter(f -> f.getType() == type).anyMatch(BaseFee::isPremium);
  }

  /** Returns the total cost of all fees and credits for the event. */
  public Money getTotalCost() {
    return Money.of(
        currency,
        Streams.concat(fees.stream(), credits.stream())
            .map(BaseFee::getCost)
            .reduce(zeroInCurrency(currency), BigDecimal::add));
  }

  public boolean hasAnyPremiumFees() {
    return fees.stream().anyMatch(BaseFee::isPremium);
  }

  /** Returns the create cost for the event. */
  public Money getCreateCost() {
    return getTotalCostForType(FeeType.CREATE);
  }

  /** Returns the EAP cost for the event. */
  public Money getEapCost() {
    return getTotalCostForType(FeeType.EAP);
  }

  /** Returns the renew cost for the event. */
  public Money getRenewCost() {
    return getTotalCostForType(FeeType.RENEW);
  }

  /** Returns the restore cost for the event. */
  public Money getRestoreCost() {
    return getTotalCostForType(FeeType.RESTORE);
  }

  /** Returns the list of fees for the event. */
  public ImmutableList<Fee> getFees() {
    return fees;
  }

  /** Returns whether a custom fee is present that requires fee extension acknowledgement. */
  public boolean isFeeExtensionRequired() {
    return feeExtensionRequired;
  }

  /** Returns the list of credits for the event. */
  public ImmutableList<Credit> getCredits() {
    return ImmutableList.copyOf(nullToEmpty(credits));
  }

  /** Returns the currency for all fees in the event. */
  public final CurrencyUnit getCurrency() {
    return currency;
  }

  /** Returns all fees and credits for the event. */
  public ImmutableList<BaseFee> getFeesAndCredits() {
    return Streams.concat(getFees().stream(), getCredits().stream()).collect(toImmutableList());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link FeesAndCredits} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<FeesAndCredits> {

    public Builder() {}

    private Builder(FeesAndCredits instance) {
      super(instance);
    }

    public Builder addFeeOrCredit(BaseFee feeOrCredit) {
      if (feeOrCredit instanceof Credit) {
        getInstance().credits =
            new ImmutableList.Builder<Credit>()
                .addAll(nullToEmptyImmutableCopy(getInstance().credits))
                .add((Credit) feeOrCredit)
                .build();
      } else {
        getInstance().fees =
            new ImmutableList.Builder<Fee>()
                .addAll(nullToEmptyImmutableCopy(getInstance().fees))
                .add((Fee) feeOrCredit)
                .build();
      }
      return this;
    }

    public Builder setFeesAndCredits(ImmutableList<BaseFee> feesAndCredits) {
      ImmutableList.Builder<Fee> feeBuilder = new ImmutableList.Builder<>();
      ImmutableList.Builder<Credit> creditBuilder = new ImmutableList.Builder<>();
      for (BaseFee feeOrCredit : feesAndCredits) {
        if (feeOrCredit instanceof Credit) {
          creditBuilder.add((Credit) feeOrCredit);
        } else {
          feeBuilder.add((Fee) feeOrCredit);
        }
      }
      getInstance().fees = feeBuilder.build();
      getInstance().credits = creditBuilder.build();
      return this;
    }

    public Builder setFeesAndCredits(BaseFee ... feesAndCredits) {
      return setFeesAndCredits(ImmutableList.copyOf(feesAndCredits));
    }

    public Builder setCurrency(CurrencyUnit currency) {
      getInstance().currency = currency;
      return this;
    }

    public Builder setFeeExtensionRequired(boolean feeExtensionRequired) {
      getInstance().feeExtensionRequired = feeExtensionRequired;
      return this;
    }

    @Override
    public FeesAndCredits build() {
      checkArgumentNotNull(getInstance().currency, "Currency must be specified in FeesAndCredits");
      getInstance().fees = nullToEmptyImmutableCopy(getInstance().fees);
      getInstance().credits = nullToEmptyImmutableCopy(getInstance().credits);
      return getInstance();
    }
  }
}
