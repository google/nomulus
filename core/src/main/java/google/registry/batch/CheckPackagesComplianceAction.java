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
package google.registry.batch;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import java.util.List;

/**
 * An action that checks all {@link PackagePromotion} objects for compliance with their max create
 * limit.
 */
@Action(
    service = Service.BACKEND,
    path = CheckPackagesComplianceAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CheckPackagesComplianceAction implements Runnable {

  public static final String PATH = "/_dr/task/checkPackagesCompliance";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  public void run() {
    tm().transact(
            () -> {
              ImmutableList<PackagePromotion> packages = tm().loadAllOf(PackagePromotion.class);
              ImmutableList.Builder<PackagePromotion> packagesOverCreateLimit =
                  new ImmutableList.Builder<>();
              for (PackagePromotion packagePromo : packages) {
                List<DomainHistory> creates =
                    jpaTm()
                        .query(
                            "FROM DomainHistory WHERE current_package_token = :token AND"
                                + " modificationTime >= :lastBilling AND type = 'DOMAIN_CREATE'",
                            DomainHistory.class)
                        .setParameter("token", packagePromo.getToken().getKey().toString())
                        .setParameter(
                            "lastBilling", packagePromo.getNextBillingDate().minusYears(1))
                        .getResultList();

                if (creates.size() > packagePromo.getMaxCreates()) {
                  int overage = creates.size() - packagePromo.getMaxCreates();
                  logger.atInfo().log(
                      "Package with package token %s has exceeded their max domain creation limit"
                          + " by %d name(s).",
                      packagePromo.getToken().getKey(), overage);
                  packagesOverCreateLimit.add(packagePromo);
                }
              }
              if (packagesOverCreateLimit.build().isEmpty()) {
                logger.atInfo().log("Found no packages over their create limit.");
              } else {
                logger.atInfo().log(
                    "Found %d packages over their create limit.",
                    packagesOverCreateLimit.build().size());
                // TODO(sarahbot@) Send email to registrar and registry informing of creation
                // overage once email template is finalized.
              }
            });
  }
}
