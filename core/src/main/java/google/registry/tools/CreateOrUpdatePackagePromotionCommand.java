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

import com.beust.jcommander.Parameter;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.PackagePromotion;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Shared base class for commands to create or update a PackagePromotion object. */
abstract class CreateOrUpdatePackagePromotionCommand extends MutatingCommand {

  @Parameter(description = "Allocation token String of the package token", required = true)
  List<String> mainParameters;

  @Parameter(
      names = "--max_domains",
      description = "Maximum concurrent active domains allowed in the package",
      required = true)
  int maxDomains;

  @Parameter(
      names = "--max_creates",
      description = "Maximum domain creations allowed in the package each year",
      required = true)
  int maxCreates;

  @Parameter(names = "--price", description = "Annual price of the package", required = true)
  Money price;

  @Parameter(
      names = "--next_billing_date",
      description = "The next date that the package should be billed for its annual fee",
      required = true)
  DateTime nextBillingDate;

  /** Returns the existing PackagePromotion (for update) or null, (for creates). */
  @Nullable
  abstract PackagePromotion getOldPackagePromotion(String token);

  abstract AllocationToken getAllocationToken(String token);

  @Nullable
  abstract DateTime getLastNotificationSent(String token);

  protected void initPackagePromotionCommand() {}

  @Override
  protected final void init() throws Exception {
    initPackagePromotionCommand();
    for (String token : mainParameters) {
      PackagePromotion oldPackage = getOldPackagePromotion(token);
      PackagePromotion.Builder builder =
          (oldPackage == null)
              ? new PackagePromotion.Builder().setToken(getAllocationToken(token))
              : oldPackage.asBuilder();

      builder.setMaxDomains(maxDomains);
      builder.setMaxCreates(maxCreates);
      builder.setPackagePrice(price);
      builder.setNextBillingDate(nextBillingDate);
      builder.setLastNotificationSent(getLastNotificationSent(token));

      PackagePromotion newPackage = builder.build();
      stageEntityChange(oldPackage, newPackage);
    }
  }
}
