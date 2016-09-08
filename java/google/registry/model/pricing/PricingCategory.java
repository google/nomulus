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
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import org.joda.money.Money;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * A Pricing Category for a premium domain if premiumListEntry lists a domain as in a specific price
 * category, then pricing for that domain will be deferred to this category
 */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class PricingCategory extends ImmutableObject implements Buildable {

  @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

  /** Universally unique name for this price category. */
  @Id String name;
  Money price;
  String comment;

  public String getName() {
    return name;
  }

  public Money getPrice() {
    return price;
  }

  public String getComment() {
    return comment;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
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

    public PricingCategory build() {
      final PricingCategory instance = getInstance();

      checkArgument(instance.getName() != null, "name cannot be null");
      checkArgument(instance.getPrice() != null, "price cannot be null");

      return super.build();
    }
  }

}
