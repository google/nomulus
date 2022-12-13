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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.persistence.VKey;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Shared base class for commands to create or update a PackagePromotion object. */
abstract class CreateOrUpdatePackagePromotionCommand extends MutatingCommand {

  @Parameter(description = "Allocation token String of the package token", required = true)
  List<String> mainParameters;

  @Nullable
  @Parameter(
      names = {"-d", "--max_domains"},
      description = "Maximum concurrent active domains allowed in the package")
  Integer maxDomains;

  @Nullable
  @Parameter(
      names = {"-c", "--max_creates"},
      description = "Maximum domain creations allowed in the package each year")
  Integer maxCreates;

  @Nullable
  @Parameter(
      names = {"-p", "--price"},
      description = "Annual price of the package")
  Money price;

  @Nullable
  @Parameter(
      names = "--next_billing_date",
      description = "The next date that the package should be billed for its annual fee")
  Date nextBillingDate;

  /** Returns the existing PackagePromotion or null if it does not exist. */
  @Nullable
  abstract PackagePromotion getOldPackagePromotion(String token);

  /** Returns the allocation token object. */
  AllocationToken getAndCheckAllocationToken(String token) {
    Optional<AllocationToken> allocationToken =
        tm().transact(() -> tm().loadByKeyIfPresent(VKey.create(AllocationToken.class, token)));
    checkArgument(
        allocationToken.isPresent(),
        "An allocation token with the token String %s does not exist. The package token must be"
            + " created first before it can be used to create a PackagePromotion",
        token);
    checkArgument(
        allocationToken.get().getTokenType().equals(TokenType.PACKAGE),
        "The allocation token must be of the PACKAGE token type");
    return allocationToken.get();
  }

  /** Does not clear the lastNotificationSent field. Subclasses can override this. */
  boolean clearLastNotificationSent() {
    return false;
  }

  @Override
  protected final void init() throws Exception {
    for (String token : mainParameters) {
      tm().transact(
              () -> {
                PackagePromotion oldPackage = getOldPackagePromotion(token);
                checkArgument(
                    oldPackage != null || price != null,
                    "PackagePrice is required when creating a new package");

                AllocationToken allocationToken = getAndCheckAllocationToken(token);

                PackagePromotion.Builder builder =
                    (oldPackage == null)
                        ? new PackagePromotion.Builder().setToken(allocationToken)
                        : oldPackage.asBuilder();

                Optional.ofNullable(maxDomains).ifPresent(builder::setMaxDomains);
                Optional.ofNullable(maxCreates).ifPresent(builder::setMaxCreates);
                Optional.ofNullable(price).ifPresent(builder::setPackagePrice);
                Optional.ofNullable(nextBillingDate)
                    .ifPresent(
                        nextBillingDate ->
                            builder.setNextBillingDate(new DateTime(nextBillingDate)));
                if (clearLastNotificationSent()) {
                  builder.setLastNotificationSent(null);
                }
                PackagePromotion newPackage = builder.build();
                stageEntityChange(oldPackage, newPackage);
              });
    }
  }
}
