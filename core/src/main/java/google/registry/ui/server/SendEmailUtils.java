// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Objects;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * Utility class for sending emails from the app.
 */
public class SendEmailUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final String gSuiteOutgoingEmailDisplayName;
  private final SendEmailService emailService;
  private final ImmutableList<String> registrarChangesNotificationEmailAddresses;

  @Inject
  public SendEmailUtils(
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      @Config("gSuiteOutgoingEmailDisplayName") String gSuiteOutgoingEmailDisplayName,
      @Config("registrarChangesNotificationEmailAddresses")
          ImmutableList<String> registrarChangesNotificationEmailAddresses,
      SendEmailService emailService) {
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.gSuiteOutgoingEmailDisplayName = gSuiteOutgoingEmailDisplayName;
    this.emailService = emailService;
    this.registrarChangesNotificationEmailAddresses = registrarChangesNotificationEmailAddresses;
  }

  /**
   * Sends an email from Nomulus to the registrarChangesNotificationEmailAddresses and the specified
   * additionalAddresses. Returns true iff sending to at least 1 address was successful.
   *
   * <p>This means that if there are no recipients ({@link #hasRecipients} returns false), this will
   * return false even thought no error happened.
   *
   * <p>This also means that if there are multiple recipients, it will return true even if some (but
   * not all) of the recipients had an error.
   */
  public boolean sendEmail(
      final String subject, String body, ImmutableList<String> additionalAddresses) {
    try {
      InternetAddress from =
          new InternetAddress(
              gSuiteOutgoingEmailAddress.getAddress(), gSuiteOutgoingEmailDisplayName);
      ImmutableList<InternetAddress> recipients =
          Stream.concat(
                  registrarChangesNotificationEmailAddresses.stream(), additionalAddresses.stream())
              .map(
                  emailAddress -> {
                    try {
                      return new InternetAddress(emailAddress, true);
                    } catch (AddressException e) {
                      logger.atSevere().withCause(e).log(
                          "Could not send email to %s with subject '%s'.", emailAddress, subject);
                      // Returning null excludes this address from the list of recipients on the
                      // email.
                      return null;
                    }
                  })
              .filter(Objects::nonNull)
              .collect(toImmutableList());
      if (recipients.isEmpty()) {
        return false;
      }
      emailService.sendEmail(
          EmailMessage.newBuilder()
              .setBody(body)
              .setSubject(subject)
              .setRecipients(recipients)
              .setFrom(from)
              .build());
      return true;
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log(
          "Could not email to addresses %s with subject '%s'.",
          Joiner.on(", ").join(registrarChangesNotificationEmailAddresses), subject);
      return false;
    }
  }

  /**
   * Sends an email from Nomulus to the registrarChangesNotificationEmailAddresses.
   *
   * <p>See {@link #sendEmail(String, String, ImmutableList)}.
   */
  public boolean sendEmail(final String subject, String body) {
    return sendEmail(subject, body, ImmutableList.of());
  }

  /**
   * Returns whether there are any recipients set up. {@link #sendEmail} will always return false if
   * there are no recipients.
   */
  public boolean hasRecipients() {
    return !registrarChangesNotificationEmailAddresses.isEmpty();
  }
}
