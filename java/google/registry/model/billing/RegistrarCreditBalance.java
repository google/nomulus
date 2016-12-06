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

package google.registry.model.billing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ForwardingNavigableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import java.util.HashMap;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * The balance of a {@link RegistrarCredit} at a given point in time.
 *
 * <p>A credit balance has two related times in addition to the monetary amount: the effective time,
 * which represents the time at which the amount becomes the actual credit balance; and the
 * written time, which represents the time at which this balance object was saved.
 *
 * <p>The active balance of a credit object before (at) any given point in time T can be found by
 * taking the balance object with the latest effective time that is before (before or at) T, and
 * breaking any ties by choosing the mostly recently written among those balances.
 */
@ReportedOn
@Entity
public final class RegistrarCreditBalance extends ImmutableObject implements Buildable {

  @Id
  long id;

  /** The registrar credit object for which this represents a balance. */
  @Parent
  Key<RegistrarCredit> parent;

  /** The time at which this balance amount should become effective. */
  DateTime effectiveTime;

  /**
   * The time at which this balance update was written.
   *
   * <p>Used to break ties in cases where there are multiple balances with the same effective time,
   * as the last written balance will take priority.
   */
  DateTime writtenTime;

  /** The monetary amount of credit balance remaining as of the effective time. */
  Money amount;

  public Key<RegistrarCredit> getParent() {
    return parent;
  }

  public DateTime getEffectiveTime() {
    return effectiveTime;
  }

  public DateTime getWrittenTime() {
    return writtenTime;
  }

  public Money getAmount() {
    return amount;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A Builder for an {@link RegistrarCreditBalance}. */
  public static class Builder extends Buildable.Builder<RegistrarCreditBalance> {

    private CurrencyUnit currency;

    public Builder() {}

    public Builder(RegistrarCreditBalance instance) {
      super(instance);
    }

    public RegistrarCreditBalance.Builder setParent(RegistrarCredit parent) {
      this.currency = parent.getCurrency();
      getInstance().parent = Key.create(parent);
      return this;
    }

    public RegistrarCreditBalance.Builder setEffectiveTime(DateTime effectiveTime) {
      getInstance().effectiveTime = effectiveTime;
      return this;
    }

    public RegistrarCreditBalance.Builder setWrittenTime(DateTime writtenTime) {
      getInstance().writtenTime = writtenTime;
      return this;
    }

    public RegistrarCreditBalance.Builder setAmount(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "Credit balance amount cannot be negative");
      getInstance().amount = amount;
      return this;
    }

    @Override
    public RegistrarCreditBalance build() {
      RegistrarCreditBalance instance = getInstance();
      checkNotNull(instance.parent);
      checkNotNull(instance.effectiveTime);
      checkNotNull(instance.writtenTime);
      checkNotNull(instance.amount);
      checkState(
          instance.amount.getCurrencyUnit().equals(currency),
          "Currency of balance amount differs from credit currency (%s vs %s)",
          instance.amount.getCurrencyUnit(),
          currency);
      return super.build();
    }
  }

  /**
   * A map of maps representing the historical credit balance information for a given credit.
   *
   * <p>Specifically, this class provides a high-level view of the balances for a given credit
   * by in essence grouping them first by effective time and then by written time.  This facilitates
   * the printing of a readable representation of a credit's balance history, and the retrieval of
   * the active balance at a given time (as described above on RegistrarCreditBalance).
   */
  public static class BalanceMap
      extends ForwardingNavigableMap<DateTime, ImmutableSortedMap<DateTime, Money>> {

    /**
     * Constructs a BalanceMap for the given registrar credit by loading all RegistrarCreditBalance
     * entities for the credit and then inserting them into a map of maps keyed first by effective
     * time and then by written time with the balance amount as the value.
     */
    public static BalanceMap createForCredit(RegistrarCredit registrarCredit) {
      // Build up the data in a mutable map of maps.
      Map<DateTime, Map<DateTime, Money>> map = new HashMap<>();
      for (RegistrarCreditBalance balance :
          ofy().load().type(RegistrarCreditBalance.class).ancestor(registrarCredit)) {
        // Create the submap at this key if it doesn't exist already.
        Map<DateTime, Money> submap =
            Optional.fromNullable(map.get(balance.effectiveTime))
                .or(new HashMap<DateTime, Money>());
        submap.put(balance.writtenTime, balance.amount);
        map.put(balance.effectiveTime, submap);
      }
      // Wrap the mutable map of maps in an immutable BalanceMap.
      return new BalanceMap(map);
    }

    /** The immutable map of maps used as the backing map. */
    private final ImmutableSortedMap<DateTime, ImmutableSortedMap<DateTime, Money>> delegate;

    /**
     * Constructs an immutable BalanceMap from balance data provided as a map of maps.
     *
     * <p>The constructed BalanceMap delegates to an immutable copy of the provided map of maps.
     * This copy is created by first making a view of the map in which each submap is replaced by
     * an immutable copy, and then making an immutable copy of that view.
     */
    @VisibleForTesting
    BalanceMap(Map<DateTime, ? extends Map<DateTime, Money>> data) {
      delegate = ImmutableSortedMap.copyOf(
          Maps.transformValues(
              data,
              new Function<Map<DateTime, Money>, ImmutableSortedMap<DateTime, Money>>() {
                @Override
                public ImmutableSortedMap<DateTime, Money> apply(Map<DateTime, Money> map) {
                  return ImmutableSortedMap.copyOf(map, Ordering.natural());
                }
              }),
          Ordering.natural());
    }

    @Override
    protected ImmutableSortedMap<DateTime, ImmutableSortedMap<DateTime, Money>> delegate() {
      return delegate;
    }

    /**
     * Returns the most recently written balance for the effective time corresponding to this entry,
     * or {@link Optional#absent()} if this entry is null.
     */
    private Optional<Money> getMostRecentlyWrittenBalance(
        Map.Entry<DateTime, ImmutableSortedMap<DateTime, Money>> balancesAtEffectiveTime) {
      return balancesAtEffectiveTime == null
          ? Optional.<Money>absent()
          // Don't use Optional.fromNullable() here since it's an error if there's a empty submap.
          : Optional.of(balancesAtEffectiveTime.getValue().lastEntry().getValue());
    }

    /**
     * Returns the active balance at a given time as described above on RegistrarCreditBalance, or
     * {@link Optional#absent()} if no balance was active at that time (i.e. the time provided is
     * before the first effectiveTime of any balance for the credit this BalanceMap represents).
     */
    public Optional<Money> getActiveBalanceAtTime(DateTime time) {
      return getMostRecentlyWrittenBalance(delegate.floorEntry(time));
    }

    /**
     * Returns the active balance before a given time as described above on RegistrarCreditBalance,
     * or {@link Optional#absent()} if no balance was active before that time (i.e. the time
     * provided is before or at the first effectiveTime of any balance for the credit).
     */
    public Optional<Money> getActiveBalanceBeforeTime(DateTime time) {
      return getMostRecentlyWrittenBalance(delegate.lowerEntry(time));
    }

    /** Returns a string representation of this BalanceMap's data. */
    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      for (Map.Entry<DateTime, ? extends Map<DateTime, Money>> entry : delegate.entrySet()) {
        builder.append(String.format(" - %s\n", entry.getKey()));
        for (Map.Entry<DateTime, Money> subEntry : entry.getValue().entrySet()) {
          builder.append(
              String.format("   - %s - %s\n", subEntry.getKey(), subEntry.getValue()));
        }
      }
      return builder.toString();
    }
  }
}
