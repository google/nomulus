// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.Iterables.toArray;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.common.net.MediaType;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.EmailMessage;
import google.registry.util.EmailMessage.Attachment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Properties;
import javax.inject.Inject;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.apache.commons.codec.binary.Base64;

/** Sends {@link EmailMessage EmailMessages} through Google Workspace using {@link Gmail}. */
public final class GmailClient {

  private final Gmail gmail;
  private final InternetAddress outgoingEmailAddressWithUsername;
  private final InternetAddress replyToEmailAddress;

  @Inject
  GmailClient(
      Gmail gmail,
      @Config("gSuiteNewOutgoingEmailAddress") String gSuiteOutgoingEmailAddress,
      @Config("gSuiteOutgoingEmailDisplayName") String gSuiteOutgoingEmailDisplayName,
      @Config("replyToEmailAddress") InternetAddress replyToEmailAddress) {

    this.gmail = gmail;
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
   *
   * <p>If the sender as specified by {@link EmailMessage#from} differs from the caller's identity,
   * the caller must have delegated `send` authority to the sender.
   */
  @CanIgnoreReturnValue
  public Message sendEmail(EmailMessage emailMessage) {
    Message message = toGmailMessage(toMimeMessage(emailMessage));
    try {
      // "me" is reserved word for the authorized user of the Gmail API.
      return gmail.users().messages().send("me", message).execute();
    } catch (IOException e) {
      throw new EmailException(e);
    }
  }

  static Message toGmailMessage(MimeMessage message) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      message.writeTo(buffer);
      byte[] rawMessageBytes = buffer.toByteArray();
      String encodedEmail = Base64.encodeBase64URLSafeString(rawMessageBytes);
      Message gmailMessage = new Message();
      gmailMessage.setRaw(encodedEmail);
      return gmailMessage;
    } catch (MessagingException | IOException e) {
      throw new EmailException(e);
    }
  }

  MimeMessage toMimeMessage(EmailMessage emailMessage) {
    try {
      MimeMessage msg =
          new MimeMessage(Session.getDefaultInstance(new Properties(), /* authenticator= */ null));
      msg.setFrom(this.outgoingEmailAddressWithUsername);
      msg.setReplyTo(new InternetAddress[] {replyToEmailAddress});
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
}
