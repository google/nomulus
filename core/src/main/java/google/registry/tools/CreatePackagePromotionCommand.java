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

import com.beust.jcommander.Parameters;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.persistence.VKey;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;

/** Command to create a PackagePromotion */
@Parameters(
    separators = " =",
    commandDescription =
        "Create new package promotion object(s) for registrars to register multiple names under"
            + " one contractual annual package price using a package allocation token")
public final class CreatePackagePromotionCommand extends CreateOrUpdatePackagePromotionCommand {

  @Nullable
  @Override
  PackagePromotion getOldPackagePromotion(String tokenString) {
    checkAllocationToken(tokenString);
    checkArgument(
        !PackagePromotion.loadByTokenString(tokenString).isPresent(),
        "PackagePromotion with token %s already exists",
        tokenString);
    return null;
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
        "An allocation token with the token String %s does not exist. The package token must be"
            + " created first before it can be used to create a PackagePromotion",
        tokenString);
    checkArgument(
        allocationToken.get().getTokenType().equals(TokenType.PACKAGE),
        "The allocation token must be of the PACKAGE token type");
    return allocationToken;
  }

  @Nullable
  @Override
  DateTime getLastNotificationSent(String token) {
    return null;
  }
}
