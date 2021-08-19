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
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.joda.time.DateTimeZone.UTC;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
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
  public static final String PATH = "/_dr/task/sendExpiringCertificateNotificationEmail";
  /**
   * Used as an offset when storing the last notification email sent date.
   *
   * <p>This is used to handle edges cases when the update happens in between the day switch. For
   * instance,if the job starts at 2:00 am every day and it finishes at 2:03 of the same day, then
   * next day at 2am, the date difference will be less than a day, which will lead to the date
   * difference between two successive email sent date being the expected email interval days + 1;
   */
  protected static final Duration UPDATE_TIME_OFFSET = Duration.standardMinutes(10);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd");

  private final CertificateChecker certificateChecker;
  private final String expirationWarningEmailBodyText;
  private final SendEmailService sendEmailService;
  private final String expirationWarningEmailSubjectText;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final Response response;

  @Inject
  public SendExpiringCertificateNotificationEmailAction(
      @Config("expirationWarningEmailBodyText") String expirationWarningEmailBodyText,
      @Config("expirationWarningEmailSubjectText") String expirationWarningEmailSubjectText,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      SendEmailService sendEmailService,
      CertificateChecker certificateChecker,
      Response response) {
    this.certificateChecker = certificateChecker;
    this.expirationWarningEmailSubjectText = expirationWarningEmailSubjectText;
    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.expirationWarningEmailBodyText = expirationWarningEmailBodyText;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      sendNotificationEmails();
      response.setStatus(SC_OK);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Exception thrown when sending expiring certificate notification emails.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Exception thrown with cause: %s", e));
    }
  }

  /**
   * Returns a list of registrars that should receive expiring notification emails. There are two
   * certificates that should be considered (the main certificate and failOver certificate). The
   * registrars should receive notifications if one of the certificate checks returns true.
   */
  @VisibleForTesting
  ImmutableList<RegistrarInfo> getRegistrarsWithExpiringCertificates() {
    return Streams.stream(Registrar.loadAllCached())
        .map(
            registrar ->
                RegistrarInfo.create(
                    registrar,
                    registrar.getClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringCertNotificationSentDate(),
                            registrar.getClientCertificate().get()),
                    registrar.getFailoverClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringFailoverCertNotificationSentDate(),
                            registrar.getFailoverClientCertificate().get())))
        .filter(
            registrarInfo ->
                registrarInfo.isCertExpiring() || registrarInfo.isFailOverCertExpiring())
        .collect(toImmutableList());
  }

  /**
   * Sends a notification email to the registrar regarding the expiring certificate and returns true
   * if it's sent successfully.
   */
  @VisibleForTesting
  boolean sendNotificationEmail(
      Registrar registrar,
      DateTime lastExpiringCertNotificationSentDate,
      CertificateType certificateType,
      Optional<String> certificate) {
    if (!certificate.isPresent()
        || !certificateChecker.shouldReceiveExpiringNotification(
            lastExpiringCertNotificationSentDate, certificate.get())) {
      return false;
    }
    try {
      ImmutableSet<InternetAddress> recipients = getEmailAddresses(registrar, Type.TECH);
      if (recipients.isEmpty()) {
        logger.atWarning().log(
            "Registrar %s contains no email addresses to receive notification email.",
            registrar.getRegistrarName());
        return false;
      }
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
      /*
       * A duration time offset is used here to ensure that date comparison between two
       * successive dates is always greater than 1 day. This date is set as last updated date,
       * for applicable certificate.
       */
      updateLastNotificationSentDate(
          registrar,
          DateTime.now(UTC).minusMinutes((int) UPDATE_TIME_OFFSET.getStandardMinutes()),
          certificateType);
      return true;
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Failed to send expiring certificate notification email to registrar %s.",
              registrar.getRegistrarName()));
    }
  }

  /** Updates the last notification sent date in database. */
  @VisibleForTesting
  void updateLastNotificationSentDate(
      Registrar registrar, DateTime now, CertificateType certificateType) {
    try {
      tm().transact(
              () -> {
                Registrar.Builder newRegistrar = tm().loadByEntity(registrar).asBuilder();
                switch (certificateType) {
                  case PRIMARY:
                    newRegistrar.setLastExpiringCertNotificationSentDate(now);
                    tm().put(newRegistrar.build());
                    logger.atInfo().log(
                        "Updated last notification email sent date for %s certificate of "
                            + "registrar %s.",
                        certificateType.getDisplayName(), registrar.getRegistrarName());
                    break;
                  case FAILOVER:
                    newRegistrar.setLastExpiringFailoverCertNotificationSentDate(now);
                    tm().put(newRegistrar.build());
                    logger.atInfo().log(
                        "Updated last notification email sent date for %s certificate of "
                            + "registrar %s.",
                        certificateType.getDisplayName(), registrar.getRegistrarName());
                    break;
                  default:
                    throw new IllegalArgumentException(
                        String.format(
                            "Unsupported certificate type: %s being passed in when updating "
                                + "the last notification sent date to registrar %s.",
                            certificateType.toString(), registrar.getRegistrarName()));
                }
              });
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Failed to update the last notification sent date to Registrar %s for the %s "
                  + "certificate.",
              registrar.getRegistrarName(), certificateType.getDisplayName()));
    }
  }

  /** Sends notification emails to registrars with expiring certificates. */
  @VisibleForTesting
  int sendNotificationEmails() {
    int emailsSent = 0;
    for (RegistrarInfo registrarInfo : getRegistrarsWithExpiringCertificates()) {
      Registrar registrar = registrarInfo.registrar();
      if (registrarInfo.isCertExpiring()) {
        sendNotificationEmail(
            registrar,
            registrar.getLastExpiringCertNotificationSentDate(),
            CertificateType.PRIMARY,
            registrar.getClientCertificate());
        emailsSent++;
      }
      if (registrarInfo.isFailOverCertExpiring()) {
        sendNotificationEmail(
            registrar,
            registrar.getLastExpiringFailoverCertNotificationSentDate(),
            CertificateType.FAILOVER,
            registrar.getFailoverClientCertificate());
        emailsSent++;
      }
    }
    logger.atInfo().log(
        "Sent %d expiring certificate notification emails to registrars.", emailsSent);
    return emailsSent;
  }

  /** Returns a list of email addresses of the registrar that should receive a notification email */
  @VisibleForTesting
  ImmutableSet<InternetAddress> getEmailAddresses(Registrar registrar, Type contactType) {
    ImmutableSortedSet<RegistrarContact> contacts = registrar.getContactsOfType(contactType);
    ImmutableSet.Builder<InternetAddress> recipientEmails = new ImmutableSet.Builder<>();
    for (RegistrarContact contact : contacts) {
      try {
        recipientEmails.add(new InternetAddress(contact.getEmailAddress()));
      } catch (AddressException e) {
        logger.atWarning().withCause(e).log(
            "Registrar Contact email address %s of Registrar %s is invalid; skipping.",
            contact.getEmailAddress(), registrar.getRegistrarName());
      }
    }
    return recipientEmails.build();
  }

  /**
   * Generates email content by taking registrar name, certificate type and expiration date as
   * parameters.
   */
  @VisibleForTesting
  @SuppressWarnings("lgtm[java/dereferenced-value-may-be-null]")
  String getEmailBody(String registrarName, CertificateType type, Date expirationDate) {
    checkArgumentNotNull(expirationDate, "Expiration date cannot be null");
    checkArgumentNotNull(type, "Certificate type cannot be null");
    return String.format(
        expirationWarningEmailBodyText,
        registrarName,
        type.getDisplayName(),
        DATE_FORMATTER.print(new DateTime(expirationDate)));
  }

  /**
   * Certificate types for X509Certificate.
   *
   * <p><b>Note:</b> These types are only used to indicate the type of expiring certificate in
   * notification emails.
   */
  protected enum CertificateType {
    PRIMARY("primary"),
    FAILOVER("fail-over");

    private final String displayName;

    CertificateType(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  @AutoValue
  public abstract static class RegistrarInfo {
    static RegistrarInfo create(
        Registrar registrar, boolean isCertExpiring, boolean isFailOverCertExpiring) {
      return new AutoValue_SendExpiringCertificateNotificationEmailAction_RegistrarInfo(
          registrar, isCertExpiring, isFailOverCertExpiring);
    }

    public abstract Registrar registrar();

    public abstract boolean isCertExpiring();

    public abstract boolean isFailOverCertExpiring();
  }
}
