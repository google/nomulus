// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Comparator;
import java.util.Date;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/** An action that sends notification emails to registrars whose certificates are expiring soon. */
@Action(
    service = Action.Service.BACKEND,
    path = SendExpiringCertificateNotificationEmailAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class SendExpiringCertificateNotificationEmailAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String PATH = "/_dr/task/sendExpiringCertificateNotificationEmail";
  /**
   * Uses as an offset when storing the last notification email sent date. This is used to handle
   * edges cases when the update happens in between the day switch. For instance,if the job starts
   * at 2:00 am every day and it finishes at 2:03 of the same day, then next day at 2am, the date
   * difference will be less than a day, which will lead to the date difference between two
   * successive email sent date being the expected email interval days + 1;
   */
  private static final Duration UPDATE_TIME_OFFSET = Duration.standardMinutes(10);

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd");

  private final SendEmailService sendEmailService;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final CertificateChecker certificateChecker;
  private final String expirationWarningEmailBodyText;
  private final String expirationWarningEmailSubjectText;

  @Inject
  public SendExpiringCertificateNotificationEmailAction(
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      @Config("expirationWarningEmailBodyText") String expirationWarningEmailBodyText,
      @Config("expirationWarningEmailSubjectText") String expirationWarningEmailSubjectText,
      SendEmailService sendEmailService,
      CertificateChecker certificateChecker) {

    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.certificateChecker = certificateChecker;
    this.expirationWarningEmailSubjectText = expirationWarningEmailSubjectText;
    this.expirationWarningEmailBodyText = expirationWarningEmailBodyText;
  }

  @Override
  public void run() {
    sendNotificationEmails();
  }

  /**
   * Returns a list of registrars that should receive expiring notification emails. There are two
   * certificates that should be considered (main certificate and failover certificate). The
   * registrars should receive notifications if one of the certificate checks returns true.
   */
  @VisibleForTesting
  ImmutableList<Registrar> getRegistrarsWithExpiringCertificates() {
    return Streams.stream(Registrar.loadAllCached())
        .sorted(Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
        .filter(
            registrar ->
                (registrar.getClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringCertNotificationSentDate(),
                            registrar.getClientCertificate().get()))
                    || (registrar.getFailoverClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringFailoverCertNotificationSentDate(),
                            registrar.getFailoverClientCertificate().get())))
        .collect(toImmutableList());
  }

  /**
   * Sends a notification email to the registrar regarding the expiring certificate and returns the
   * date and time when a notification email is sent.
   */
  @VisibleForTesting
  DateTime sendNotificationEmail(
      Registrar registrar,
      DateTime lastExpiringCertNotificationSentDate,
      CertificateType certificateType,
      Optional<String> certificate) {
    if (certificate.isPresent()
        && certificateChecker.shouldReceiveExpiringNotification(
            lastExpiringCertNotificationSentDate, certificate.get())) {
      try {
        ImmutableSet<InternetAddress> recipients = getEmailAddresses(registrar, Type.TECH);
        if (!recipients.isEmpty()) {
          sendEmailService.sendEmail(
              EmailMessage.newBuilder()
                  .setFrom(gSuiteOutgoingEmailAddress)
                  .setSubject(expirationWarningEmailSubjectText)
                  .setBody(
                      getEmailBody(
                          registrar.getRegistrarName(),
                          certificateType,
                          certificateChecker.getCertificate(certificate.get()).getNotAfter()))
                  .setRecipients(recipients)
                  .setCcs(getEmailAddresses(registrar, Type.ADMIN))
                  .build());
          updateLastNotificationSentDate(
              registrar,
              DateTime.now(UTC).minusMinutes((int) UPDATE_TIME_OFFSET.getStandardMinutes()),
              certificateType);
        } else {
          logger.atWarning().log(
              "Registrar %s contains no email addresses to receive notification email.",
              registrar.getRegistrarName());
        }
      } catch (Exception e) {
        logger.atWarning().withCause(e).log(
            "Failed to send expiring certificate notification email to registrar %s",
            registrar.getRegistrarName());
      }
    }
    return START_OF_TIME;
  }

  /** Updates the last notification sent date in database. */
  @VisibleForTesting
  void updateLastNotificationSentDate(
      Registrar registrar, DateTime lastNotificationSentDate, CertificateType certificateType) {
    /*
     * Uses an offset to ensure that date comparison between two successive dates is always greater
     * than 1 day. This date is set as last updated date, for applicable certificate.
     */
    try {
      tm().transact(
              () -> {
                Registrar.Builder newRegistrar = tm().loadByEntity(registrar).asBuilder();
                if (certificateType == CertificateType.FAILOVER) {
                  newRegistrar.setLastExpiringFailoverCertNotificationSentDate(
                      lastNotificationSentDate);
                  tm().put(newRegistrar.build());
                } else if (certificateType == CertificateType.PRIMARY) {
                  newRegistrar.setLastExpiringCertNotificationSentDate(lastNotificationSentDate);
                  tm().put(newRegistrar.build());
                } else {
                  logger.atWarning().log("Unsupported Certificate Type: %s", certificateType);
                }
              });
      logger.atInfo().log(
          "Updated Last Notification Email Sent Date for %s Certificate of Registrar %.",
          certificateType.displayName, registrar.getRegistrarName());
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Failed to update the last notification sent date to Registrar %s for the %s "
              + "certificate.",
          registrar.getRegistrarName(), certificateType.displayName);
    }
  }

  /** Sends notification emails to registrars with expiring certificates. */
  @VisibleForTesting
  void sendNotificationEmails() {
    for (Registrar registrar : getRegistrarsWithExpiringCertificates()) {
      sendNotificationEmail(
          registrar,
          registrar.getLastExpiringCertNotificationSentDate(),
          CertificateType.PRIMARY,
          registrar.getClientCertificate());
      sendNotificationEmail(
          registrar,
          registrar.getLastExpiringFailoverCertNotificationSentDate(),
          CertificateType.FAILOVER,
          registrar.getFailoverClientCertificate());
    }
    logger.atInfo().log(
        "Notification Emails have been attempted to send to all registrars that contain "
            + "expiring certificate(s).");
  }

  /**
   * Returns a list of email addresses of the registrar that should receive the notification email
   */
  @VisibleForTesting
  ImmutableSet<InternetAddress> getEmailAddresses(Registrar registrar, Type contactType) {
    ImmutableSortedSet<RegistrarContact> contacts = registrar.getContactsOfType(contactType);
    ImmutableSet.Builder<InternetAddress> recipientEmails = new ImmutableSet.Builder<>();
    for (RegistrarContact contact : contacts) {
      try {
        recipientEmails.add(new InternetAddress(contact.getEmailAddress()));
      } catch (AddressException e) {
        logger.atWarning().withCause(e).log(
            "Contact email address %s is invalid for contact %s; skipping.",
            contact.getEmailAddress(), contact.getName());
      }
    }
    return recipientEmails.build();
  }

  /**
   * Generates email content by taking registrar name, certificate type and expiration date as
   * parameters.
   */
  @VisibleForTesting
  String getEmailBody(String registrarName, CertificateType type, Date expirationDate) {
    checkArgumentNotNull(expirationDate);
    checkArgumentNotNull(type);
    return String.format(
        expirationWarningEmailBodyText,
        registrarName,
        type.displayName,
        DATE_FORMATTER.print(new DateTime(expirationDate)));
  }

  /**
   * Certificate types for X509Certificate.
   *
   * <p><b>Note:</b> These types are only used to indicate the type of expiring certificate in
   * notification emails.
   */
  public enum CertificateType {
    PRIMARY("Primary"),
    FAILOVER("Failover");

    private final String displayName;

    CertificateType(String display) {
      this.displayName = display;
    }

    public String getDisplayName() {
      return displayName;
    }
  }
}
