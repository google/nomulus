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

import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.BulkPricingPackage;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.auth.Auth;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.Days;

/**
 * An action that checks all {@link BulkPricingPackage} objects for compliance with their max create
 * limit.
 */
@Action(service = GaeService.BACKEND, path = CheckBulkComplianceAction.PATH, auth = Auth.AUTH_ADMIN)
public class CheckBulkComplianceAction implements Runnable {

  public static final String PATH = "/_dr/task/checkBulkCompliance";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final SendEmailUtils sendEmailUtils;
  private final Clock clock;
  private final String bulkPricingPackageCreateLimitEmailSubject;
  private final String bulkPricingPackageDomainLimitWarningEmailSubject;
  private final String bulkPricingPackageDomainLimitUpgradeEmailSubject;
  private final String bulkPricingPackageCreateLimitEmailBody;
  private final String bulkPricingPackageDomainLimitWarningEmailBody;
  private final String bulkPricingPackageDomainLimitUpgradeEmailBody;
  private final String registrySupportEmail;
  private static final int THIRTY_DAYS = 30;
  private static final int FORTY_DAYS = 40;

  @Inject
  public CheckBulkComplianceAction(
      SendEmailUtils sendEmailUtils,
      Clock clock,
      @Config("bulkPricingPackageCreateLimitEmailSubject")
          String bulkPricingPackageCreateLimitEmailSubject,
      @Config("bulkPricingPackageDomainLimitWarningEmailSubject")
          String bulkPricingPackageDomainLimitWarningEmailSubject,
      @Config("bulkPricingPackageDomainLimitUpgradeEmailSubject")
          String bulkPricingPackageDomainLimitUpgradeEmailSubject,
      @Config("bulkPricingPackageCreateLimitEmailBody")
          String bulkPricingPackageCreateLimitEmailBody,
      @Config("bulkPricingPackageDomainLimitWarningEmailBody")
          String bulkPricingPackageDomainLimitWarningEmailBody,
      @Config("bulkPricingPackageDomainLimitUpgradeEmailBody")
          String bulkPricingPackageDomainLimitUpgradeEmailBody,
      @Config("registrySupportEmail") String registrySupportEmail) {
    this.sendEmailUtils = sendEmailUtils;
    this.clock = clock;
    this.bulkPricingPackageCreateLimitEmailSubject = bulkPricingPackageCreateLimitEmailSubject;
    this.bulkPricingPackageDomainLimitWarningEmailSubject =
        bulkPricingPackageDomainLimitWarningEmailSubject;
    this.bulkPricingPackageDomainLimitUpgradeEmailSubject =
        bulkPricingPackageDomainLimitUpgradeEmailSubject;
    this.bulkPricingPackageCreateLimitEmailBody = bulkPricingPackageCreateLimitEmailBody;
    this.bulkPricingPackageDomainLimitWarningEmailBody =
        bulkPricingPackageDomainLimitWarningEmailBody;
    this.bulkPricingPackageDomainLimitUpgradeEmailBody =
        bulkPricingPackageDomainLimitUpgradeEmailBody;
    this.registrySupportEmail = registrySupportEmail;
  }

  @Override
  public void run() {
    tm().transact(TRANSACTION_REPEATABLE_READ, this::checkBulkPackages);
  }

  private void checkBulkPackages() {
    ImmutableList<BulkPricingPackage> bulkPricingPackages =
        tm().loadAllOf(BulkPricingPackage.class);
    ImmutableMap.Builder<BulkPricingPackage, Long> bulkPricingPackagesOverCreateLimitBuilder =
        new ImmutableMap.Builder<>();
    ImmutableMap.Builder<BulkPricingPackage, Long>
        bulkPricingPackagesOverActiveDomainsLimitBuilder = new ImmutableMap.Builder<>();
    for (BulkPricingPackage bulkPricingPackage : bulkPricingPackages) {
      Long creates =
          (Long)
              tm().query(
                      "SELECT COUNT(*) FROM DomainHistory WHERE resource.currentBulkToken ="
                          + " :token AND modificationTime >= :lastBilling AND type ="
                          + " 'DOMAIN_CREATE'")
                  .setParameter("token", bulkPricingPackage.getToken())
                  .setParameter(
                      "lastBilling", bulkPricingPackage.getNextBillingDate().minusYears(1))
                  .getSingleResult();
      if (creates > bulkPricingPackage.getMaxCreates()) {
        long overage = creates - bulkPricingPackage.getMaxCreates();
        logger.atInfo().log(
            "Bulk pricing package with bulk token %s has exceeded their max domain creation limit"
                + " by %d name(s).",
            bulkPricingPackage.getToken().getKey(), overage);
        bulkPricingPackagesOverCreateLimitBuilder.put(bulkPricingPackage, creates);
      }

      Long activeDomains =
          tm().query(
                  "SELECT COUNT(*) FROM Domain WHERE currentBulkToken = :token"
                      + " AND deletionTime = :endOfTime",
                  Long.class)
              .setParameter("token", bulkPricingPackage.getToken())
              .setParameter("endOfTime", END_OF_TIME)
              .getSingleResult();
      if (activeDomains > bulkPricingPackage.getMaxDomains()) {
        int overage = Ints.saturatedCast(activeDomains) - bulkPricingPackage.getMaxDomains();
        logger.atInfo().log(
            "Bulk pricing package with bulk token %s has exceed their max active domains limit by"
                + " %d name(s).",
            bulkPricingPackage.getToken().getKey(), overage);
        bulkPricingPackagesOverActiveDomainsLimitBuilder.put(bulkPricingPackage, activeDomains);
      }
    }
    handleBulkPricingPackageCreationOverage(bulkPricingPackagesOverCreateLimitBuilder.build());
    handleActiveDomainOverage(bulkPricingPackagesOverActiveDomainsLimitBuilder.build());
  }

  private void handleBulkPricingPackageCreationOverage(
      ImmutableMap<BulkPricingPackage, Long> overageList) {
    if (overageList.isEmpty()) {
      logger.atInfo().log("Found no bulk pricing packages over their create limit.");
      return;
    }
    logger.atInfo().log(
        "Found %d bulk pricing packages over their create limit.", overageList.size());
    for (BulkPricingPackage bulkPricingPackage : overageList.keySet()) {
      AllocationToken bulkToken = tm().loadByKey(bulkPricingPackage.getToken());
      Optional<Registrar> registrar =
          Registrar.loadByRegistrarIdCached(
              Iterables.getOnlyElement(bulkToken.getAllowedRegistrarIds()));
      if (registrar.isPresent()) {
        String body =
            String.format(
                bulkPricingPackageCreateLimitEmailBody,
                bulkPricingPackage.getId(),
                bulkToken.getToken(),
                registrar.get().getRegistrarName(),
                bulkPricingPackage.getMaxCreates(),
                overageList.get(bulkPricingPackage));
        sendNotification(
            bulkToken, bulkPricingPackageCreateLimitEmailSubject, body, registrar.get());
      } else {
        throw new IllegalStateException(
            String.format("Could not find registrar for bulk token %s", bulkToken));
      }
    }
  }

  private void handleActiveDomainOverage(ImmutableMap<BulkPricingPackage, Long> overageList) {
    if (overageList.isEmpty()) {
      logger.atInfo().log("Found no bulk pricing packages over their active domains limit.");
      return;
    }
    logger.atInfo().log(
        "Found %d bulk pricing packages over their active domains limit.", overageList.size());
    for (BulkPricingPackage bulkPricingPackage : overageList.keySet()) {
      int daysSinceLastNotification =
          bulkPricingPackage
              .getLastNotificationSent()
              .map(sentDate -> Days.daysBetween(sentDate, clock.nowUtc()).getDays())
              .orElse(Integer.MAX_VALUE);
      // Send a warning email if 30-39 days since last notification and an upgrade email if 40+ days
      if (daysSinceLastNotification >= THIRTY_DAYS) {
        sendActiveDomainOverageEmail(
            /* warning= */ daysSinceLastNotification >= FORTY_DAYS,
            bulkPricingPackage,
            overageList.get(bulkPricingPackage));
      }
    }
  }

  private void sendActiveDomainOverageEmail(
      boolean warning, BulkPricingPackage bulkPricingPackage, long activeDomains) {
    String emailSubject =
        warning
            ? bulkPricingPackageDomainLimitWarningEmailSubject
            : bulkPricingPackageDomainLimitUpgradeEmailSubject;
    String emailTemplate =
        warning
            ? bulkPricingPackageDomainLimitWarningEmailBody
            : bulkPricingPackageDomainLimitUpgradeEmailBody;
    AllocationToken bulkToken = tm().loadByKey(bulkPricingPackage.getToken());
    Optional<Registrar> registrar =
        Registrar.loadByRegistrarIdCached(
            Iterables.getOnlyElement(bulkToken.getAllowedRegistrarIds()));
    if (registrar.isPresent()) {
      String body =
          String.format(
              emailTemplate,
              bulkPricingPackage.getId(),
              bulkToken.getToken(),
              registrar.get().getRegistrarName(),
              bulkPricingPackage.getMaxDomains(),
              activeDomains);
      sendNotification(bulkToken, emailSubject, body, registrar.get());
      tm().put(bulkPricingPackage.asBuilder().setLastNotificationSent(clock.nowUtc()).build());
    } else {
      throw new IllegalStateException(
          String.format("Could not find registrar for bulk token %s", bulkToken));
    }
  }

  private void sendNotification(
      AllocationToken bulkToken, String subject, String body, Registrar registrar) {
    logger.atInfo().log(
        String.format(
            "Compliance email sent to support regarding the %s registrar and the bulk pricing"
                + " package with token %s.",
            registrar.getRegistrarName(), bulkToken.getToken()));
    sendEmailUtils.sendEmail(subject, body, ImmutableList.of(registrySupportEmail));
  }
}
