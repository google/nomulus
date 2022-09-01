// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain.token;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.JodaMoneyType;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.hibernate.annotations.Columns;
import org.hibernate.annotations.Type;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** An entity representing a package promotion. */
@Entity
@javax.persistence.Table(indexes = {@javax.persistence.Index(columnList = "token")})
public class PackagePromotion extends ImmutableObject implements Buildable {

  /** An autogenerated identifier for the package promotion. */
  @Id long packagePromotionId;

  /** The allocation token string for the package. */
  @Column(nullable = false)
  VKey<AllocationToken> token;

  /** The maximum number of active domains the package allows at any given time. */
  @Column(nullable = false)
  int maxDomains;

  /** The maximum number of domains that can be created in the package each year. */
  @Column(nullable = false)
  int maxCreates;

  /** The annual price of the package. */
  @Type(type = JodaMoneyType.TYPE_NAME)
  @Columns(
      columns = {
        @Column(name = "package_price_amount", nullable = false),
        @Column(name = "package_price_currency", nullable = false)
      })
  Money packagePrice;

  /** The next billing date of the package. */
  @Column(nullable = false)
  DateTime nextBillingDate = END_OF_TIME;

  /** Date the last warning email was sent that the package has exceeded the maxDomains limit. */
  @Nullable DateTime lastNotificationSent;

  public VKey<AllocationToken> getToken() {
    return token;
  }

  public int getMaxDomains() {
    return maxDomains;
  }

  public int getMaxCreates() {
    return maxCreates;
  }

  public Money getPackagePrice() {
    return packagePrice;
  }

  public DateTime getNextBillingDate() {
    return nextBillingDate;
  }

  public Optional<DateTime> getLastNotificationSent() {
    return Optional.ofNullable(lastNotificationSent);
  }

  /** Loads and returns a PackagePromotion entity by its token string directly from Cloud SQL. */
  public static Optional<PackagePromotion> loadByTokenString(String tokenString) {
    return tm().transact(
            () ->
                jpaTm()
                    .query("FROM PackagePromotion WHERE token = :token", PackagePromotion.class)
                    .setParameter("token", VKey.createSql(AllocationToken.class, tokenString))
                    .getResultStream()
                    .findFirst());
  }

  @Override
  public VKey<PackagePromotion> createVKey() {
    return VKey.createSql(PackagePromotion.class, packagePromotionId);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PackagePromotion} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<PackagePromotion> {
    public Builder() {}

    private Builder(PackagePromotion instance) {
      super(instance);
    }

    @Override
    public PackagePromotion build() {
      checkArgumentNotNull(getInstance().token, "Allocation token must be specified");
      AllocationToken allocationToken = tm().transact(() -> tm().loadByKey(getInstance().token));
      checkArgument(
          allocationToken.tokenType == TokenType.PACKAGE,
          "Allocation token must be a PACKAGE type");
      return super.build();
    }

    public Builder setToken(AllocationToken token) {
      checkArgumentNotNull(token, "Allocation token must not be null");
      checkArgument(
          token.tokenType == TokenType.PACKAGE, "Allocation token must be a PACKAGE type");
      getInstance().token = token.createVKey();
      return this;
    }

    public Builder setMaxDomains(int maxDomains) {
      checkArgumentNotNull(maxDomains, "maxDomains must not be null");
      getInstance().maxDomains = maxDomains;
      return this;
    }

    public Builder setMaxCreates(int maxCreates) {
      checkArgumentNotNull(maxCreates, "maxCreates must not be null");
      getInstance().maxCreates = maxCreates;
      return this;
    }

    public Builder setPackagePrice(Money packagePrice) {
      checkArgumentNotNull(packagePrice, "Package price must not be null");
      getInstance().packagePrice = packagePrice;
      return this;
    }

    public Builder setNextBillingDate(DateTime nextBillingDate) {
      checkArgumentNotNull(nextBillingDate, "Next billing date must not be null");
      getInstance().nextBillingDate = nextBillingDate;
      return this;
    }

    public Builder setLastNotificationSent(@Nullable DateTime lastNotificationSent) {
      getInstance().lastNotificationSent = lastNotificationSent;
      return this;
    }
  }
}
