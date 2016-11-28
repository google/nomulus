// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.pricing;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Parent;
import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;

import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.concurrent.ExecutionException;

/**
 * A Pricing Category for a premium domain if premiumListEntry lists a domain as in a specific price
 * category, then pricing for that domain will be deferred to this category
 */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class PricingCategory extends ImmutableObject
    implements Buildable, Comparable<PricingCategory> {

  @Ignore
  public static final String UNINITIALIZED = "UNINITIALIZED";

  @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

  /** Universally unique name for this price category. */
  @Id String name;

  Money price;
  String comment;

  // Used to track when this PriceCategory has been assigned to a CategorizedListEntry or TLD
  DateTime activationDate;

  public static Optional<PricingCategory> get(String name) {
    try {
      return Optional.of(cache.get(name));
    } catch (CacheLoader.InvalidCacheLoadException e) {
      return Optional.absent();
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException("Could not retrieve pricing category named " + name, e);
    }
  }

  /** Returns a PricingCategory that has not been locally cached. */
  public static Optional<PricingCategory> getUncached(final String name) {
    return Optional.fromNullable(ofy().doTransactionless(new Work<PricingCategory>() {
      @Override
      public PricingCategory run() {
        return ofy()
                   .load()
                   .type(PricingCategory.class)
                   .parent(getCrossTldKey())
                   .id(name)
                   .now();
      }}));
  }

  private static LoadingCache<String, PricingCategory> cache =
    CacheBuilder.newBuilder()
      .expireAfterWrite(
        RegistryEnvironment.get().config().getDomainLabelListCacheDuration().getMillis(),
        MILLISECONDS)
      .build(new CacheLoader<String, PricingCategory>() {
        @Override
        public PricingCategory load(final String name) throws Exception {
          return ofy().doTransactionless(new Work<PricingCategory>() {
            @Override
            public PricingCategory run() {
              return ofy()
                .load()
                .type(PricingCategory.class)
                .parent(getCrossTldKey())
                .id(name)
                .now();
            }});
        }});

  public String getName() {
    return name;
  }

  public Money getPrice() {
    return price;
  }

  public String getComment() {
    return comment;
  }

  public DateTime getActivationDate() {
    return activationDate;
  }


  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public int compareTo(PricingCategory o) {
    return o.getName().compareTo(this.getName());
  }

  public static class Builder extends Buildable.Builder<PricingCategory> {
    public Builder() {}

    public Builder(PricingCategory instance) {
      super(instance);
    }

    public Builder setName(String pricingCategoryName) {
      getInstance().name = pricingCategoryName;
      return this;
    }

    public Builder setPrice(Money price) {
      getInstance().price = price;
      return this;
    }

    public Builder setComment(String comment) {
      getInstance().comment = comment;
      return this;
    }

    /**
     * Activate this category, setting its activation date to now.  A category is activated
     * the first time it is associated with a premium name or a TLD in order to prevent
     * updates after that.
     * @return a Builder object
     */
    public Builder activate() {
      getInstance().activationDate = (getInstance().activationDate == null)
          ? DateTime.now(DateTimeZone.UTC)
          : getInstance().activationDate;
      return this;
    }

    public PricingCategory build() {
      final PricingCategory instance = getInstance();

      checkArgument(instance.getName() != null, "name cannot be null");
      checkArgument(instance.getPrice() != null, "price cannot be null");

      return super.build();
    }
  }
}
