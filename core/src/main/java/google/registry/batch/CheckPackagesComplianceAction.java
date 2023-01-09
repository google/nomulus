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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.Days;

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
  private final SendEmailUtils sendEmailUtils;
  private final Clock clock;
  private final String packageCreateLimitEmailSubject;
  private final String packageDomainLimitWarningEmailSubject;
  private final String packageDomainLimitUpgradeEmailSubject;
  private final String packageCreateLimitEmailBody;
  private final String packageDomainLimitWarningEmailBody;
  private final String packageDomainLimitUpgradeEmailBody;
  private final String registrySupportEmail;
  private static final int THIRTY_DAYS = 30;
  private static final int FORTY_DAYS = 40;

  @Inject
  public CheckPackagesComplianceAction(
      SendEmailUtils sendEmailUtils,
      Clock clock,
      @Config("packageCreateLimitEmailSubject") String packageCreateLimitEmailSubject,
      @Config("packageDomainLimitWarningEmailSubject") String packageDomainLimitWarningEmailSubject,
      @Config("packageDomainLimitUpgradeEmailSubject") String packageDomainLimitUpgradeEmailSubject,
      @Config("packageCreateLimitEmailBody") String packageCreateLimitEmailBody,
      @Config("packageDomainLimitWarningEmailBody") String packageDomainLimitWarningEmailBody,
      @Config("packageDomainLimitUpgradeEmailBody") String packageDomainLimitUpgradeEmailBody,
      @Config("registrySupportEmail") String registrySupportEmail) {
    this.sendEmailUtils = sendEmailUtils;
    this.clock = clock;
    this.packageCreateLimitEmailSubject = packageCreateLimitEmailSubject;
    this.packageDomainLimitWarningEmailSubject = packageDomainLimitWarningEmailSubject;
    this.packageDomainLimitUpgradeEmailSubject = packageDomainLimitUpgradeEmailSubject;
    this.packageCreateLimitEmailBody = packageCreateLimitEmailBody;
    this.packageDomainLimitWarningEmailBody = packageDomainLimitWarningEmailBody;
    this.packageDomainLimitUpgradeEmailBody = packageDomainLimitUpgradeEmailBody;
    this.registrySupportEmail = registrySupportEmail;
  }

  @Override
  public void run() {
    tm().transact(this::checkPackages);
  }

  private void checkPackages() {
    ImmutableList<PackagePromotion> packages = tm().loadAllOf(PackagePromotion.class);
    ImmutableList.Builder<PackagePromotion> packagesOverCreateLimitBuilder =
        new ImmutableList.Builder<>();
    ImmutableMap.Builder<PackagePromotion, Integer> packagesOverActiveDomainsLimitBuilder =
        new ImmutableMap.Builder<>();
    for (PackagePromotion packagePromo : packages) {
      Long creates =
          (Long)
              tm().query(
                      "SELECT COUNT(*) FROM DomainHistory WHERE current_package_token ="
                          + " :token AND modificationTime >= :lastBilling AND type ="
                          + " 'DOMAIN_CREATE'")
                  .setParameter("token", packagePromo.getToken().getKey().toString())
                  .setParameter("lastBilling", packagePromo.getNextBillingDate().minusYears(1))
                  .getSingleResult();
      if (creates > packagePromo.getMaxCreates()) {
        int overage = Ints.saturatedCast(creates) - packagePromo.getMaxCreates();
        logger.atInfo().log(
            "Package with package token %s has exceeded their max domain creation limit"
                + " by %d name(s).",
            packagePromo.getToken().getKey(), overage);
        packagesOverCreateLimitBuilder.add(packagePromo);
      }

      Long activeDomains =
          tm().query(
                  "SELECT COUNT(*) FROM Domain WHERE currentPackageToken = :token"
                      + " AND deletionTime = :endOfTime",
                  Long.class)
              .setParameter("token", packagePromo.getToken())
              .setParameter("endOfTime", END_OF_TIME)
              .getSingleResult();
      if (activeDomains > packagePromo.getMaxDomains()) {
        int overage = Ints.saturatedCast(activeDomains) - packagePromo.getMaxDomains();
        logger.atInfo().log(
            "Package with package token %s has exceed their max active domains limit by"
                + " %d name(s).",
            packagePromo.getToken().getKey(), overage);
        packagesOverActiveDomainsLimitBuilder.put(packagePromo, Ints.saturatedCast(creates));
      }
    }
    handlePackageCreationOverage(packagesOverCreateLimitBuilder.build());
    handleActiveDomainOverage(packagesOverActiveDomainsLimitBuilder.build());
  }

  private void handlePackageCreationOverage(ImmutableList<PackagePromotion> overageList) {
    if (overageList.isEmpty()) {
      logger.atInfo().log("Found no packages over their create limit.");
      return;
    }
    logger.atInfo().log("Found %d packages over their create limit.", overageList.size());
    for (PackagePromotion packagePromotion : overageList) {
      AllocationToken packageToken = tm().loadByKey(packagePromotion.getToken());
      Optional<Registrar> registrar =
          Registrar.loadByRegistrarIdCached(
              Iterables.getOnlyElement(packageToken.getAllowedRegistrarIds()));
      if (registrar.isPresent()) {
        String body =
            String.format(
                packageCreateLimitEmailBody,
                packagePromotion.getId(),
                packageToken.getToken(),
                registrar.get().getRegistrarName(),
                packagePromotion.getMaxCreates());
        sendNotification(packageToken, packageCreateLimitEmailSubject, body, registrar.get());
      } else {
        throw new IllegalStateException(
            String.format("Could not find registrar for package token %s", packageToken));
      }
    }
  }

  private void handleActiveDomainOverage(ImmutableMap<PackagePromotion, Integer> overageList) {
    if (overageList.isEmpty()) {
      logger.atInfo().log("Found no packages over their active domains limit.");
      return;
    }
    logger.atInfo().log("Found %d packages over their active domains limit.", overageList.size());
    for (PackagePromotion packagePromotion : overageList.keySet()) {
      int daysSinceLastNotification =
          packagePromotion
              .getLastNotificationSent()
              .map(sentDate -> Days.daysBetween(sentDate, clock.nowUtc()).getDays())
              .orElse(Integer.MAX_VALUE);
      if (daysSinceLastNotification < THIRTY_DAYS) {
        // Don't send an email if notification was already sent within the last 30
        // days
        continue;
      } else if (daysSinceLastNotification < FORTY_DAYS) {
        // Send an upgrade email if last email was between 30 and 40 days ago
        sendActiveDomainOverageEmail(
            /* warning= */ false, packagePromotion, overageList.get(packagePromotion));
      } else {
        // Send a warning email
        sendActiveDomainOverageEmail(
            /* warning= */ true, packagePromotion, overageList.get(packagePromotion));
      }
    }
  }

  private void sendActiveDomainOverageEmail(
      boolean warning, PackagePromotion packagePromotion, int activeDomains) {
    String emailSubject =
        warning ? packageDomainLimitWarningEmailSubject : packageDomainLimitUpgradeEmailSubject;
    String emailTemplate =
        warning ? packageDomainLimitWarningEmailBody : packageDomainLimitUpgradeEmailBody;
    AllocationToken packageToken = tm().loadByKey(packagePromotion.getToken());
    Optional<Registrar> registrar =
        Registrar.loadByRegistrarIdCached(
            Iterables.getOnlyElement(packageToken.getAllowedRegistrarIds()));
    if (registrar.isPresent()) {
      String body =
          String.format(
              emailTemplate,
              packagePromotion.getId(),
              packageToken.getToken(),
              registrar.get().getRegistrarName(),
              packagePromotion.getMaxDomains(),
              activeDomains);
      sendNotification(packageToken, emailSubject, body, registrar.get());
      tm().put(packagePromotion.asBuilder().setLastNotificationSent(clock.nowUtc()).build());
    } else {
      throw new IllegalStateException(
          String.format("Could not find registrar for package token %s", packageToken));
    }
  }

  private void sendNotification(
      AllocationToken packageToken, String subject, String body, Registrar registrar) {
    logger.atInfo().log(
        String.format(
            "Compliance email sent to support regarding the %s registrar and the package with token"
                + " %s.",
            registrar.getRegistrarName(), packageToken.getToken()));
    sendEmailUtils.sendEmail(subject, body, ImmutableList.of(registrySupportEmail));
  }
}
