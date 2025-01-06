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

package google.registry.groups;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.toArray;

import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import dagger.Lazy;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.EmailMessage;
import google.registry.util.EmailMessage.Attachment;
import google.registry.util.Retrier;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.function.Predicate;
import javax.inject.Inject;

/** Sends {@link EmailMessage EmailMessages} through Google Workspace using {@link Gmail}. */
public final class GmailClient {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Lazy<Gmail> gmail;
  private final Retrier retrier;
  private final boolean isEmailSendingEnabled;
  private final InternetAddress outgoingEmailAddressWithUsername;
  private final InternetAddress replyToEmailAddress;

  @Inject
  GmailClient(
      Lazy<Gmail> gmail,
      Retrier retrier,
      @Config("isEmailSendingEnabled") boolean isEmailSendingEnabled,
      @Config("gSuiteNewOutgoingEmailAddress") String gSuiteOutgoingEmailAddress,
      @Config("gSuiteOutgoingEmailDisplayName") String gSuiteOutgoingEmailDisplayName,
      @Config("replyToEmailAddress") InternetAddress replyToEmailAddress) {

    this.gmail = gmail;
    this.retrier = retrier;
    this.isEmailSendingEnabled = isEmailSendingEnabled;
    this.replyToEmailAddress = replyToEmailAddress;
    try {
      this.outgoingEmailAddressWithUsername =
          new InternetAddress(gSuiteOutgoingEmailAddress, gSuiteOutgoingEmailDisplayName);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Sends {@code emailMessage} using {@link Gmail}.
   */
  public void sendEmail(EmailMessage emailMessage) {
    if (!isEmailSendingEnabled) {
      logger.atInfo().log(
          String.format(
              "Email with subject %s would have been sent to recipients %s",
              emailMessage.subject().substring(0, Math.min(emailMessage.subject().length(), 15)),
              String.join(
                  " , ",
                  emailMessage.recipients().stream()
                      .map(ia -> ia.toString())
                      .collect(toImmutableSet()))));
      return;
    }
    Message message = toGmailMessage(toMimeMessage(emailMessage));
    // Unlike other Cloud APIs such as GCS and SecretManager, Gmail does not retry on errors.
    retrier.callWithRetry(
        // "me" is reserved word for the authorized user of the Gmail API.
        () -> this.gmail.get().users().messages().send("me", message).execute(),
        RetriableGmailExceptionPredicate.INSTANCE);
  }

  static Message toGmailMessage(MimeMessage message) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      message.writeTo(buffer);
      byte[] rawMessageBytes = buffer.toByteArray();
      return new Message().encodeRaw(rawMessageBytes);
    } catch (MessagingException | IOException e) {
      throw new EmailException(e);
    }
  }

  MimeMessage toMimeMessage(EmailMessage emailMessage) {
    try {
      MimeMessage msg =
          new MimeMessage(Session.getDefaultInstance(new Properties(), /* authenticator= */ null));
      msg.setFrom(this.outgoingEmailAddressWithUsername);
      msg.setReplyTo(
          new InternetAddress[] {emailMessage.replyToEmailAddress().orElse(replyToEmailAddress)});
      msg.addRecipients(
          RecipientType.TO, toArray(emailMessage.recipients(), InternetAddress.class));
      msg.setSubject(emailMessage.subject());

      Multipart multipart = new MimeMultipart();
      BodyPart bodyPart = new MimeBodyPart();
      bodyPart.setContent(
          emailMessage.body(),
          emailMessage.contentType().orElse(MediaType.PLAIN_TEXT_UTF_8).toString());
      multipart.addBodyPart(bodyPart);

      if (emailMessage.attachment().isPresent()) {
        Attachment attachment = emailMessage.attachment().get();
        BodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setContent(attachment.content(), attachment.contentType().toString());
        attachmentPart.setFileName(attachment.filename());
        attachmentPart.setDisposition(MimeBodyPart.ATTACHMENT);
        multipart.addBodyPart(attachmentPart);
      }
      msg.addRecipients(RecipientType.BCC, toArray(emailMessage.bccs(), Address.class));
      msg.addRecipients(RecipientType.CC, toArray(emailMessage.ccs(), Address.class));
      msg.setContent(multipart);
      msg.saveChanges();
      return msg;
    } catch (MessagingException e) {
      throw new EmailException(e);
    }
  }

  static class EmailException extends RuntimeException {

    public EmailException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Determines if a Gmail API exception may be retried.
   *
   * <p>See <a href="https://developers.google.com/gmail/api/guides/handle-errors">online doc</a>
   * for details.
   */
  static class RetriableGmailExceptionPredicate implements Predicate<Throwable> {

    static RetriableGmailExceptionPredicate INSTANCE = new RetriableGmailExceptionPredicate();

    private static final int USAGE_LIMIT_EXCEEDED = 403;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int BACKEND_ERROR = 500;

    private static final ImmutableSet<String> TRANSIENT_OVERAGE_REASONS =
        ImmutableSet.of("userRateLimitExceeded", "rateLimitExceeded");

    @Override
    public boolean test(Throwable e) {
      if (e instanceof HttpResponseException) {
        return testHttpResponse((HttpResponseException) e);
      }
      return true;
    }

    private boolean testHttpResponse(HttpResponseException e) {
      int statusCode = e.getStatusCode();
      if (statusCode == TOO_MANY_REQUESTS || statusCode == BACKEND_ERROR) {
        return true;
      }
      if (statusCode == USAGE_LIMIT_EXCEEDED) {
        return TRANSIENT_OVERAGE_REASONS.contains(e.getStatusMessage());
      }
      return false;
    }
  }
}
