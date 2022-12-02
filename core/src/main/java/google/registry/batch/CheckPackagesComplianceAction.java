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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.PackagePromotion;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;

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
    tm().transact(
            () -> {
              ImmutableList<PackagePromotion> packages = tm().loadAllOf(PackagePromotion.class);
              ImmutableList.Builder<PackagePromotion> packagesOverCreateLimit =
                  new ImmutableList.Builder<>();
              ImmutableList.Builder<PackagePromotion> packagesOverActiveDomainsLimit =
                  new ImmutableList.Builder<>();
              for (PackagePromotion packagePromo : packages) {
                Long creates =
                    (Long)
                        tm().query(
                                "SELECT COUNT(*) FROM DomainHistory WHERE current_package_token ="
                                    + " :token AND modificationTime >= :lastBilling AND type ="
                                    + " 'DOMAIN_CREATE'")
                            .setParameter("token", packagePromo.getToken().getKey().toString())
                            .setParameter(
                                "lastBilling", packagePromo.getNextBillingDate().minusYears(1))
                            .getSingleResult();
                if (creates > packagePromo.getMaxCreates()) {
                  int overage = Ints.saturatedCast(creates) - packagePromo.getMaxCreates();
                  logger.atInfo().log(
                      "Package with package token %s has exceeded their max domain creation limit"
                          + " by %d name(s).",
                      packagePromo.getToken().getKey(), overage);
                  packagesOverCreateLimit.add(packagePromo);
                }

                Long activeDomains =
                    (Long)
                        tm().query(
                                "SELECT COUNT(*) FROM Domain WHERE current_package_token = :token"
                                    + " AND deletion_time = :time")
                            .setParameter("token", packagePromo.getToken().getKey().toString())
                            .setParameter("time", END_OF_TIME.toDate())
                            .getSingleResult();

                if (activeDomains > packagePromo.getMaxDomains()) {
                  int overage = Ints.saturatedCast(activeDomains) - packagePromo.getMaxDomains();
                  logger.atInfo().log(
                      "Package with package token %s has exceed their max active domains limit by"
                          + " %d name(s).",
                      packagePromo.getToken().getKey(), overage);
                  packagesOverActiveDomainsLimit.add(packagePromo);
                }
              }
              if (packagesOverCreateLimit.build().isEmpty()) {
                logger.atInfo().log("Found no packages over their create limit.");
              } else {
                logger.atInfo().log(
                    "Found %d packages over their create limit.",
                    packagesOverCreateLimit.build().size());
                for (PackagePromotion packagePromotion : packagesOverCreateLimit.build()) {
                  AllocationToken packageToken = tm().loadByKey(packagePromotion.getToken());
                  Optional<Registrar> registrar =
                      Registrar.loadByRegistrarIdCached(
                          packageToken.getAllowedRegistrarIds().iterator().next());
                  if (registrar.isPresent()) {
                    String body =
                        String.format(
                            packageCreateLimitEmailBody,
                            registrar.get().getRegistrarName(),
                            packageToken.getToken(),
                            registrySupportEmail);
                    sendNotification(
                        packageToken, packageCreateLimitEmailSubject, body, registrar.get());
                  } else {
                    logger.atSevere().log(
                        String.format(
                            "Could not find registrar for package token %s", packageToken));
                  }
                }
              }

              if (packagesOverActiveDomainsLimit.build().isEmpty()) {
                logger.atInfo().log("Found no packages over their active domains limit.");
              } else {
                logger.atInfo().log(
                    "Found %d packages over their active domains limit.",
                    packagesOverActiveDomainsLimit.build().size());
                for (PackagePromotion packagePromotion : packagesOverActiveDomainsLimit.build()) {
                  // Determine if email should be 30 day warning or upgrade notification
                  String emailTemplate;
                  String emailSubject;
                  if (packagePromotion.getLastNotificationSent().isPresent()) {
                    if (packagePromotion
                        .getLastNotificationSent()
                        .get()
                        .isAfter(clock.nowUtc().minusDays(30))) {
                      // Don't send an email if notification was already sent within the last 30
                      // days
                      continue;
                    } else if (packagePromotion
                        .getLastNotificationSent()
                        .get()
                        .isBefore(clock.nowUtc().minusDays(40))) {
                      // Send a warning if last warning email was more than 40 days ago
                      emailTemplate = packageDomainLimitWarningEmailBody;
                      emailSubject = packageDomainLimitWarningEmailSubject;
                    } else {
                      // Send an upgrade email if last email was between 30 and 40 days ago
                      emailTemplate = packageDomainLimitUpgradeEmailBody;
                      emailSubject = packageDomainLimitUpgradeEmailSubject;
                    }
                  } else {
                    // if no previous email has been sent, send a warning email
                    emailTemplate = packageDomainLimitWarningEmailBody;
                    emailSubject = packageDomainLimitWarningEmailSubject;
                  }

                  AllocationToken packageToken = tm().loadByKey(packagePromotion.getToken());
                  Optional<Registrar> registrar =
                      Registrar.loadByRegistrarIdCached(
                          packageToken.getAllowedRegistrarIds().iterator().next());
                  if (registrar.isPresent()) {
                    String body =
                        String.format(
                            emailTemplate,
                            registrar.get().getRegistrarName(),
                            packageToken.getToken(),
                            registrySupportEmail);
                    sendNotification(packageToken, emailSubject, body, registrar.get());
                    tm().put(
                            packagePromotion
                                .asBuilder()
                                .setLastNotificationSent(clock.nowUtc())
                                .build());
                  } else {
                    logger.atSevere().log(
                        String.format(
                            "Could not find registrar for package token %s", packageToken));
                  }
                }
              }
            });
  }

  private void sendNotification(
      AllocationToken packageToken, String subject, String body, Registrar registrar) {
    logger.atInfo().log(
        String.format(
            "Compliance email sent to the %s registrar regarding the package with token" + " %s.",
            registrar.getRegistrarName(), packageToken.getToken()));
    sendEmailUtils.sendEmail(
        subject,
        body,
        Optional.of(registrySupportEmail),
        registrar.getContacts().stream()
            .filter(c -> c.getTypes().contains(RegistrarPoc.Type.ADMIN))
            .map(RegistrarPoc::getEmailAddress)
            .collect(toImmutableList()));
  }
}
