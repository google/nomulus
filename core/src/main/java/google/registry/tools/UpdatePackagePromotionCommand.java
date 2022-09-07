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
import com.beust.jcommander.Parameters;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.persistence.VKey;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;

/** Command to update a PackagePromotion */
@Parameters(separators = " =", commandDescription = "Update package promotion object(s)")
public final class UpdatePackagePromotionCommand extends CreateOrUpdatePackagePromotionCommand {

  @Parameter(
      names = "--clear_last_notification_sent",
      description =
          "Clear the date last max-domain non-compliance notification was sent? (This should be"
              + " cleared whenever a tier is upgraded)")
  boolean clearLastNotificationSent;

  @Override
  PackagePromotion getOldPackagePromotion(String tokenString) {
    checkAllocationToken(tokenString);
    Optional<PackagePromotion> oldPackage = PackagePromotion.loadByTokenString(tokenString);
    checkArgument(
        oldPackage.isPresent(), "PackagePromotion with token %s does not exist", tokenString);
    return oldPackage.get();
  }

  @Override
  AllocationToken getAllocationToken(String tokenString) {
    Optional<AllocationToken> allocationToken = checkAllocationToken(tokenString);
    return allocationToken.get();
  }

  private Optional<AllocationToken> checkAllocationToken(String tokenString) {
    Optional<AllocationToken> allocationToken =
        tm().transact(
                () -> tm().loadByKeyIfPresent(VKey.createSql(AllocationToken.class, tokenString)));
    checkArgument(
        allocationToken.isPresent(),
        "An allocation token with the token String %s does not exist",
        tokenString);
    return allocationToken;
  }

  @Nullable
  @Override
  DateTime getLastNotificationSent(String token) {
    if (clearLastNotificationSent) {
      return null;
    }
    return getOldPackagePromotion(token).getLastNotificationSent().orElse(null);
  }
}
