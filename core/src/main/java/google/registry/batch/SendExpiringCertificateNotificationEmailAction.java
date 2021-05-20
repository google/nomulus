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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
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
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.graalvm.compiler.api.replacements.Fold.InjectedParameter;
import org.joda.time.DateTime;

/* An action that sends notification emails to registrars whose certificates are expiring soon.
 * */
@Action(
    service = Action.Service.BACKEND,
    path = SendExpiringCertificateNotificationEmailAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class SendExpiringCertificateNotificationEmailAction implements Runnable {

  public static final String PATH = "/_dr/task/sendExpiringCertificationNotificationEmail";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final SendEmailService sendEmailService;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final CertificateChecker certificateChecker;

  @Inject
  public SendExpiringCertificateNotificationEmailAction(
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      SendEmailService sendEmailService,
      @InjectedParameter CertificateChecker certificateChecker) {

    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.certificateChecker = certificateChecker;
  }

  @Override
  public void run() {
    sendNotificationEmails();
  }

  // 1. get a list of registrars
  private ImmutableList<Registrar> getRegistrarsWithExpiringCertificates() {
    return Streams.stream(Registrar.loadAllCached())
        .sorted(Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
        .filter(
            registrar ->
                certificateChecker.shouldReceiveExpiringNotification(
                        registrar.getLastExpiringFailoverCertNotificationSentDate(),
                        registrar.getFailoverClientCertificate().get())
                    || certificateChecker.shouldReceiveExpiringNotification(
                        registrar.getLastExpiringNotificationSentDate(),
                        registrar.getClientCertificate().get()))
        .collect(toImmutableList());
  }

  // 2. send email to certificate that is expiring
  private void sendNotificationEmails() {
    logger.atInfo().log("Getting list of registrars with expiring certificates");
    ImmutableList<Registrar> registrars = getRegistrarsWithExpiringCertificates();
    // TODO: remove placeholder text once there's more info on email from business side
    String subject = "random subject";

    for (Registrar registrar : registrars) {
      String certificateStr = null, failOverCertificateStr = null;
      if (registrar.getClientCertificate().isPresent()
          && certificateChecker.shouldReceiveExpiringNotification(
              registrar.getLastExpiringNotificationSentDate(),
              registrar.getClientCertificate().get())) {
        certificateStr = registrar.getClientCertificate().get();
      }
      if (registrar.getFailoverClientCertificate().isPresent()
          && certificateChecker.shouldReceiveExpiringNotification(
              registrar.getLastExpiringFailoverCertNotificationSentDate(),
              registrar.getFailoverClientCertificate().get())) {
        failOverCertificateStr = registrar.getFailoverClientCertificate().get();
      }
      if (certificateStr != null || failOverCertificateStr != null) {
        // using an offset when updating time when dealing with cases when a few minutes of
        // difference means a different day; 
        int updateTimeOffset = 10;
        try {
          sendEmailService.sendEmail(
              EmailMessage.newBuilder()
                  .setFrom(gSuiteOutgoingEmailAddress)
                  .setBody(getEmailBody(certificateStr, failOverCertificateStr))
                  .setSubject(subject)
                  .setRecipients(getEmailAddresses(registrar, Type.TECH))
                  .setBccs(getEmailAddresses(registrar, Type.ADMIN))
                  .build());

          // update registrar's last notification email sent date, if applicable
          if (certificateStr != null) {
            tm().transact(
                    () ->
                        tm().put(
                                registrar
                                    .asBuilder()
                                    .setLastExpiringNotificationSentDate(
                                        DateTime.now().minusMinutes(updateTimeOffset))));
          }
          if (failOverCertificateStr != null) {
            tm().transact(
                    () ->
                        tm().put(
                                registrar
                                    .asBuilder()
                                    .setLastExpiringFailoverCertNotificationSentDate(
                                        DateTime.now().minusMinutes(updateTimeOffset))));
          }
        } catch (Exception e) {
          logger.atWarning().withCause(e).log(
              "Sending email regarding expiring certificates to registrar %s failed, check "
                  + "logs for more details",
              registrar.getRegistrarName());
        }
      }
    }
  }

  // returns a list of email addresses that should receive the same email
  private ImmutableSet<InternetAddress> getEmailAddresses(Registrar registrar, Type contactType) {
    ImmutableSortedSet<RegistrarContact> contacts = registrar.getContactsOfType(contactType);
    ImmutableSet.Builder<InternetAddress> recipientEmails = new Builder<>();
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

  // TODO: get email content from business side
  // generates email body with certificate strings as parameters;
  private String getEmailBody(String certificateStr, String failOverCertificateStr) {
    if (certificateStr.isEmpty() && failOverCertificateStr.isEmpty()) {
      return "";
    }
    return String.format("expiring certificate(s): %s %s", certificateStr, failOverCertificateStr);
  }
}
