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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.assertTldExists;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** A per-registrar billing credit, applied toward future charges for registrar activity. */
@ReportedOn
@Entity
public final class RegistrarCredit extends ImmutableObject implements Buildable {

  /**
   * The type of credit represented.  The ordering below determines the order in which credits of
   * of different types will be applied to an invoice charge.
   */
  // Note: Right now the ordering is actually maintained manually via a hard-coded table in the
  // relevant billing query, so if adding a credit type here, add it there as well.
  // TODO(b/19031546): make the query automatically reflect the order in this enum.
  public enum CreditType {
    /** Credit awarded as an incentive to participate in sunrise/landrush auctions. */
    AUCTION("Auction Credit"),

    /** Credit awarded as part of a promotional deal. */
    PROMOTION("Promotional Credit");

    /** A descriptive name for a credit of this type. */
    private String descriptiveName;

    CreditType(String descriptiveName) {
      this.descriptiveName = descriptiveName;
    }

    public String getDescriptiveName() {
      return descriptiveName;
    }
  }

  @Id
  long id;

  /** The registrar to whom this credit belongs. */
  @Parent
  Key<Registrar> parent;

  /** The type of credit. */
  CreditType type;

  /**
   * The time that this credit was created.  If a registrar has multiple credits of a given type,
   * the older credits will be applied first.
   */
  DateTime creationTime;

  /** The currency in which the balance for this credit is stored. */
  CurrencyUnit currency;

  /** The line item description to use when displaying this credit on an invoice. */
  String description;

  /**
   * The TLD in which this credit applies.
   *
   * <p>For auction credits, this is also the TLD for which the relevant auctions occurred.
   */
  String tld;

  public Key<Registrar> getParent() {
    return parent;
  }

  public CreditType getType() {
    return type;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public CurrencyUnit getCurrency() {
    return currency;
  }

  public String getDescription() {
    return description;
  }

  public String getTld() {
    return tld;
  }

  /** Returns a string representation of this credit. */
  public String getSummary() {
    String fields = Joiner.on(' ').join(type, creationTime, tld);
    return String.format("%s (%s/%d) - %s", description, parent.getName(), id, fields);
  }

  /** Returns the default description for this {@link RegistrarCredit} instance. */
  private String getDefaultDescription() {
    return type.getDescriptiveName() + " for ." + tld;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A Builder for {@link RegistrarCredit}. */
  public static class Builder extends Buildable.Builder<RegistrarCredit> {
    public Builder() {}

    public Builder(RegistrarCredit instance) {
      super(instance);
    }

    public Builder setParent(Registrar parent) {
      getInstance().parent = Key.create(parent);
      return this;
    }

    public Builder setType(CreditType type) {
      getInstance().type = type;
      return this;
    }

    public Builder setCreationTime(DateTime creationTime) {
      getInstance().creationTime = creationTime;
      return this;
    }

    public Builder setCurrency(CurrencyUnit currency) {
      getInstance().currency = currency;
      return this;
    }

    public Builder setDescription(String description) {
      getInstance().description = description;
      return this;
    }

    public Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }

    @Override
    public RegistrarCredit build() {
      RegistrarCredit instance = getInstance();
      checkNotNull(instance.parent, "parent credit");
      checkNotNull(instance.type, "type");
      checkNotNull(instance.creationTime, "creationTime");
      checkNotNull(instance.currency, "currency");
      assertTldExists(checkNotNull(instance.tld, "tld"));
      checkArgument(
          Registry.get(instance.tld).getCurrency().equals(instance.currency),
          "Credits must be in the currency of the assigned TLD");
      instance.description =
          Optional.fromNullable(instance.description).or(instance.getDefaultDescription());
      return super.build();
    }
  }

  /** Ordering that sorts credits first by type and then by creation time. */
  private static final Ordering<RegistrarCredit> CREDIT_PRIORITY_ORDERING =
      new Ordering<RegistrarCredit>() {
        @Override
        public int compare(RegistrarCredit left, RegistrarCredit right) {
          return ComparisonChain.start()
              .compare(left.type, right.type)
              .compare(left.creationTime, right.creationTime)
              .result();
        }
      };

  /**
   * Loads all RegistrarCredit entities for the given Registrar.
   *
   * <p>The resulting list sorts the credits first by type and then by creation time.
   */
  public static ImmutableList<RegistrarCredit> loadAllForRegistrar(Registrar registrar) {
    return FluentIterable.from(ofy().load().type(RegistrarCredit.class).ancestor(registrar))
        .toSortedList(CREDIT_PRIORITY_ORDERING);
  }
}
