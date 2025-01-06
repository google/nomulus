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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.domain.token.BulkPricingPackage;
import java.util.List;

/** Command to show a {@link BulkPricingPackage} object. */
@Parameters(separators = " =", commandDescription = "Show bulk pricing package object(s)")
public class GetBulkPricingPackageCommand extends GetEppResourceCommand {

  @Parameter(description = "Bulk pricing token(s)", required = true)
  private List<String> mainParameters;

  @Override
  void runAndPrint() {
    for (String token : mainParameters) {
      tm().transact(
              () -> {
                BulkPricingPackage bulkPricingPackage =
                    checkArgumentPresent(
                        BulkPricingPackage.loadByTokenString(token),
                        "BulkPricingPackage with token %s does not exist",
                        token);
                System.out.println(bulkPricingPackage);
              });
    }
  }
}
