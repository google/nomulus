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
import google.registry.model.domain.token.PackagePromotion;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;

/**
 * An action that checks all {@link PackagePromotion} objects for compliance with their max create
 * and active domain limits.
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
    ImmutableList<PackagePromotion> packages =
        tm().transact(() -> tm().loadAllOf(PackagePromotion.class));
    ImmutableList.Builder<PackagePromotion> packagesOverCreateLimit = new ImmutableList.Builder<>();
    for (PackagePromotion packagePromo : packages) {
      // load all domainHistory events with packagetoken equal to package's token and is a create.
      // And occurred since 1 year before nextBillingDate
      int createCount =
          (Integer)
              jpaTm()
                  .transact(
                      () ->
                          jpaTm()
                              .getEntityManager()
                              .createQuery(
                                  "SELECT COUNT(*) FROM PackagePromotion WHERE currentPackageToken"
                                      + " = :token AND creationTime >= :creationTime AND"
                                      + " historyType = DOMAIN_CREATE")
                              .setParameter("token", packagePromo.getToken().getSqlKey())
                              .setParameter(
                                  "creationTime", packagePromo.getNextBillingDate().minusYears(1))
                              .getSingleResult());
      if (createCount > packagePromo.getMaxCreates()) {
        packagesOverCreateLimit.add(packagePromo);
      }
    }
    if (packagesOverCreateLimit.build().isEmpty()) {
      logger.atInfo().log("Found 0 packages over their create limit.");
    } else {
      logger.atInfo().log(
          "Found %d packages over their create limit.", packagesOverCreateLimit.build().size());
    }
  }
}
