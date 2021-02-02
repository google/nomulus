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

package google.registry.model.registry.label;

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.hash.Funnels.stringFunnel;
import static com.google.common.hash.Funnels.unencodedCharsFunnel;
import static google.registry.model.ofy.ObjectifyService.allocateId;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.BloomFilter;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.registry.Registry;
import google.registry.schema.replay.DatastoreOnlyEntity;
import google.registry.schema.replay.NonReplicatedEntity;
import google.registry.schema.tld.PremiumListSqlDao;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.PostLoad;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.hibernate.LazyInitializationException;
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
@Entity
@javax.persistence.Entity
@Table(indexes = {@Index(columnList = "name", name = "premiumlist_name_idx")})
public final class PremiumList extends BaseDomainLabelList<Money, PremiumList.PremiumListEntry>
    implements NonReplicatedEntity {

  /** Stores the revision key for the set of currently used premium list entry entities. */
  @Transient Key<PremiumListRevision> revisionKey;

  @Ignore
  @Column(nullable = false)
  CurrencyUnit currency;

  @Ignore
  @ElementCollection
  @CollectionTable(
      name = "PremiumEntry",
      joinColumns = @JoinColumn(name = "revisionId", referencedColumnName = "revisionId"))
  @MapKeyColumn(name = "domainLabel")
  @Column(name = "price", nullable = false)
  Map<String, BigDecimal> labelsToPrices;

  @Ignore
  @Column(nullable = false)
  BloomFilter<String> bloomFilter;

  /** Virtual parent entity for premium list entry entities associated with a single revision. */
  @ReportedOn
  @Entity
  public static class PremiumListRevision extends ImmutableObject implements DatastoreOnlyEntity {

    @Parent Key<PremiumList> parent;

    @Id long revisionId;

    /**
     * A Bloom filter that is used to determine efficiently and quickly whether a label might be
     * premium.
     *
     * <p>If the label might be premium, then the premium list entry must be loaded by key and
     * checked for existence.  Otherwise, we know it's not premium, and no Datastore load is
     * required.
     */
    private BloomFilter<String> probablePremiumLabels;

    /**
     * Get the Bloom filter.
     *
     * <p>Note that this is not a copy, but the mutable object itself, because copying would be
     * expensive. You probably should not modify the filter unless you know what you're doing.
     */
    public BloomFilter<String> getProbablePremiumLabels() {
      return probablePremiumLabels;
    }

    /**
     * The maximum size of the Bloom filter.
     *
     * <p>Trying to set it any larger will throw an error, as we know it won't fit into a Datastore
     * entity. We use 90% of the 1 MB Datastore limit to leave some wriggle room for the other
     * fields and miscellaneous entity serialization overhead.
     */
    private static final int MAX_BLOOM_FILTER_BYTES = 900000;

    /** Returns a new PremiumListRevision for the given key and premium list map. */
    @VisibleForTesting
    public static PremiumListRevision create(PremiumList parent, Set<String> premiumLabels) {
      PremiumListRevision revision = new PremiumListRevision();
      revision.parent = Key.create(parent);
      revision.revisionId = allocateId();
      // All premium list labels are already punycoded, so don't perform any further character
      // encoding on them.
      revision.probablePremiumLabels =
          BloomFilter.create(unencodedCharsFunnel(), premiumLabels.size());
      premiumLabels.forEach(revision.probablePremiumLabels::put);
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        revision.probablePremiumLabels.writeTo(bos);
        checkArgument(
            bos.size() <= MAX_BLOOM_FILTER_BYTES,
            "Too many premium labels were specified; Bloom filter exceeds max entity size");
      } catch (IOException e) {
        throw new IllegalStateException("Could not serialize premium labels Bloom filter", e);
      }
      return revision;
    }
  }

  @VisibleForTesting
  public Key<PremiumListRevision> getRevisionKey() {
    return revisionKey;
  }

  /** Returns the {@link CurrencyUnit} used for this list. */
  public CurrencyUnit getCurrency() {
    return currency;
  }

  /**
   * Returns a {@link Map} of domain labels to prices.
   *
   * <p>Note that this is lazily loaded and thus will throw a {@link LazyInitializationException} if
   * used outside the transaction in which the given entity was loaded. You generally should not be
   * using this anyway as it's inefficient to load all of the PremiumEntry rows if you don't need
   * them. To check prices, use {@link PremiumListSqlDao#getPremiumPrice} instead.
   */
  @Nullable
  public ImmutableMap<String, BigDecimal> getLabelsToPrices() {
    return labelsToPrices == null ? null : ImmutableMap.copyOf(labelsToPrices);
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
   * A premium list entry entity, persisted to Datastore. Each instance represents the price of a
   * single label on a given TLD.
   */
  @ReportedOn
  @Entity
  public static class PremiumListEntry extends DomainLabelEntry<Money, PremiumListEntry>
      implements Buildable, DatastoreOnlyEntity {

    @Parent
    Key<PremiumListRevision> parent;

    Money price;

    @Override
    public Money getValue() {
      return price;
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for constructing {@link PremiumListEntry} objects, since they are immutable. */
    public static class Builder extends DomainLabelEntry.Builder<PremiumListEntry, Builder> {

      public Builder() {}

      private Builder(PremiumListEntry instance) {
        super(instance);
      }

      public Builder setParent(Key<PremiumListRevision> parentKey) {
        getInstance().parent = parentKey;
        return this;
      }

      public Builder setPrice(Money price) {
        getInstance().price = price;
        return this;
      }
    }
  }

  @Override
  @Nullable
  PremiumListEntry createFromLine(String originalLine) {
    List<String> lineAndComment = splitOnComment(originalLine);
    if (lineAndComment.isEmpty()) {
      return null;
    }
    String line = lineAndComment.get(0);
    String comment = lineAndComment.get(1);
    List<String> parts = Splitter.on(',').trimResults().splitToList(line);
    checkArgument(parts.size() == 2, "Could not parse line in premium list: %s", originalLine);
    return new PremiumListEntry.Builder()
        .setLabel(parts.get(0))
        .setPrice(Money.parse(parts.get(1)))
        .setComment(comment)
        .build();
  }

  @Override
  public boolean refersToKey(Registry registry, Key<? extends BaseDomainLabelList<?, ?>> key) {
    return Objects.equals(registry.getPremiumList(), key);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PremiumList} objects, since they are immutable.  */
  public static class Builder extends BaseDomainLabelList.Builder<PremiumList, Builder> {

    public Builder() {}

    private Builder(PremiumList instance) {
      super(instance);
    }

    public Builder setRevision(Key<PremiumListRevision> revision) {
      getInstance().revisionKey = revision;
      return this;
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

  @PrePersist
  void prePersist() {
    lastUpdateTime = creationTime;
  }

  @PostLoad
  void postLoad() {
    creationTime = lastUpdateTime;
  }
}
