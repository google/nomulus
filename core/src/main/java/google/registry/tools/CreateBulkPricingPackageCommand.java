// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.beust.jcommander.Parameters;
import google.registry.model.domain.token.BulkPricingPackage;
import org.jetbrains.annotations.Nullable;

/** Command to create a {@link BulkPricingPackage} */
@Parameters(
    separators = " =",
    commandDescription =
        "Create new bulk pricing package object(s) for registrars to register multiple names under"
            + " one contractual annual bulk price using a bulk pricing allocation token")
public final class CreateBulkPricingPackageCommand extends CreateOrUpdateBulkPricingPackageCommand {

  @Nullable
  @Override
  BulkPricingPackage getOldBulkPricingPackage(String tokenString) {
    checkArgument(
        BulkPricingPackage.loadByTokenString(tokenString).isEmpty(),
        "BulkPricingPackage with token %s already exists",
        tokenString);
    return null;
  }
}
