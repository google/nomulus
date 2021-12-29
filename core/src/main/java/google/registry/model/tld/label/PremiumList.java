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

package google.registry.model.tld.label;

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.hash.Funnels.stringFunnel;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.BloomFilter;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.model.tld.Registry;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * A premium list entity that is used to check domain label prices.
 *
 * <p>Note that the primary key of this entity is {@link #revisionId}, which is auto-generated by
 * the database. So, if a retry of insertion happens after the previous attempt unexpectedly
 * succeeds, we will end up with having two exact same premium lists that differ only by revisionId.
 * This is fine though, because we only use the list with the highest revisionId.
 */
@ReportedOn
@javax.persistence.Entity
@Table(indexes = {@Index(columnList = "name", name = "premiumlist_name_idx")})
public final class PremiumList extends BaseDomainLabelList<BigDecimal, PremiumEntry>
    implements SqlOnlyEntity {

  @Column(nullable = false)
  CurrencyUnit currency;

  /**
   * Mapping from unqualified domain names to their prices.
   *
   * <p>This field requires special treatment since we want to lazy load it. We have to remove it
   * from the immutability contract so we can modify it after construction and we have to handle the
   * database processing on our own so we can detach it after load.
   */
  @ImmutableObject.Insignificant @Transient ImmutableMap<String, BigDecimal> labelsToPrices;

  @Column(nullable = false)
  BloomFilter<String> bloomFilter;

  /** Returns the {@link CurrencyUnit} used for this list. */
  public CurrencyUnit getCurrency() {
    return currency;
  }

  /**
   * Returns a {@link Map} of domain labels to prices.
   *
   * <p>Note that this is lazily loaded and thus must be called inside a transaction. You generally
   * should not be using this anyway as it's inefficient to load all of the PremiumEntry rows if you
   * don't need them. To check prices, use {@link PremiumListDao#getPremiumPrice} instead.
   */
  public synchronized ImmutableMap<String, BigDecimal> getLabelsToPrices() {
    if (labelsToPrices == null) {
      labelsToPrices =
          PremiumListDao.loadAllPremiumEntries(name).stream()
              .collect(
                  toImmutableMap(
                      PremiumEntry::getDomainLabel,
                      // Set the correct amount of precision for the premium list's currency.
                      premiumEntry -> convertAmountToMoney(premiumEntry.getValue()).getAmount()));
    }
    return labelsToPrices;
  }

  /**
   * Converts a raw {@link BigDecimal} amount to a {@link Money} by applying the list's currency.
   */
  public Money convertAmountToMoney(BigDecimal amount) {
    return Money.of(currency, amount.setScale(currency.getDecimalPlaces(), RoundingMode.HALF_EVEN));
  }

  /**
   * Returns a Bloom filter to determine whether a label might be premium, or is definitely not.
   *
   * <p>If the domain label might be premium, then the next step is to check for the existence of a
   * corresponding row in the PremiumListEntry table. Otherwise, we know for sure it's not premium,
   * and no DB load is required.
   */
  public BloomFilter<String> getBloomFilter() {
    return bloomFilter;
  }

  /**
   * A premium list entry entity, persisted to Cloud SQL. Each instance represents the price of a
   * single label on a given TLD.
   */
  @javax.persistence.Entity(name = "PremiumEntry")
  public static class PremiumEntry extends DomainLabelEntry<BigDecimal, PremiumList.PremiumEntry>
      implements Buildable, SqlOnlyEntity, Serializable {

    @ImmutableObject.Insignificant @javax.persistence.Id Long revisionId;

    @Column(nullable = false)
    BigDecimal price;

    @Override
    public BigDecimal getValue() {
      return price;
    }

    public static PremiumEntry create(Long revisionId, BigDecimal price, String label) {
      return new PremiumEntry.Builder()
          .setRevisionId(revisionId)
          .setPrice(price)
          .setLabel(label)
          .build();
    }

    @Override
    public String toString() {
      return String.format("%s, %s", domainLabel, price);
    }

    public String toString(CurrencyUnit currencyUnit) {
      return String.format("%s,%s %s", domainLabel, currencyUnit, price);
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for constructing {@link PremiumEntry} objects, since they are immutable. */
    public static class Builder extends DomainLabelEntry.Builder<PremiumEntry, Builder> {

      public Builder() {}

      private Builder(PremiumEntry instance) {
        super(instance);
      }

      public Builder setPrice(BigDecimal price) {
        getInstance().price = price;
        return this;
      }

      public Builder setRevisionId(Long revisionId) {
        getInstance().revisionId = revisionId;
        return this;
      }
    }
  }

  @Override
  @Nullable
  PremiumEntry createFromLine(String originalLine) {
    List<String> lineAndComment = splitOnComment(originalLine);
    if (lineAndComment.isEmpty()) {
      return null;
    }
    String line = lineAndComment.get(0);
    List<String> parts = Splitter.on(',').trimResults().splitToList(line);
    checkArgument(parts.size() == 2, "Could not parse line in premium list: %s", originalLine);
    List<String> moneyParts = Splitter.on(' ').trimResults().splitToList(parts.get(1));
    if (moneyParts.size() == 2 && this.currency != null) {
      if (!Money.parse(parts.get(1)).getCurrencyUnit().equals(this.currency)) {
        throw new IllegalArgumentException(
            String.format("The currency unit must be %s", this.currency.getCode()));
      }
    }
    BigDecimal price =
        moneyParts.size() == 2
            ? Money.parse(parts.get(1)).getAmount()
            : new BigDecimal(parts.get(1));
    return new PremiumEntry.Builder()
        .setLabel(parts.get(0))
        .setPrice(price)
        .setRevisionId(revisionId)
        .build();
  }

  @Override
  public boolean refersToList(Registry registry, String name) {
    return Objects.equals(registry.getPremiumListName().orElse(null), name);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PremiumList} objects, since they are immutable. */
  public static class Builder extends BaseDomainLabelList.Builder<PremiumList, Builder> {

    public Builder() {}

    private Builder(PremiumList instance) {
      super(instance);
    }

    public Builder setCurrency(CurrencyUnit currency) {
      getInstance().currency = currency;
      return this;
    }

    public Builder setLabelsToPrices(Map<String, BigDecimal> labelsToPrices) {
      getInstance().labelsToPrices = ImmutableMap.copyOf(labelsToPrices);
      return this;
    }

    @Override
    public PremiumList build() {
      if (getInstance().labelsToPrices != null) {
        // ASCII is used for the charset because all premium list domain labels are stored
        // punycoded.
        getInstance().bloomFilter =
            BloomFilter.create(stringFunnel(US_ASCII), getInstance().labelsToPrices.size());
        getInstance()
            .labelsToPrices
            .keySet()
            .forEach(label -> getInstance().bloomFilter.put(label));
      }
      return super.build();
    }
  }
}
