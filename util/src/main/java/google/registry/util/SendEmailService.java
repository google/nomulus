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

package google.registry.util;

import static com.google.common.collect.Iterables.toArray;

import com.google.common.net.MediaType;
import google.registry.util.EmailMessage.Attachment;
import java.io.IOException;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Wrapper around javax.mail's email creation and sending functionality. Encompasses a retry policy
 * as well.
 */
@Singleton
public class SendEmailService {

  private final Retrier retrier;
  private final TransportEmailSender transportEmailSender;

  @Inject
  SendEmailService(Retrier retrier, TransportEmailSender transportEmailSender) {
    this.retrier = retrier;
    this.transportEmailSender = transportEmailSender;
  }

  /**
   * Converts the provided message content into a {@link javax.mail.Message} and sends it with retry
   * on transient failures.
   */
  public void sendEmail(EmailMessage emailMessage) {
    retrier.callWithRetry(
        () -> {
          Message msg =
              new MimeMessage(
                  Session.getDefaultInstance(new Properties(), /* authenticator= */ null));
          msg.setFrom(emailMessage.from());
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
          transportEmailSender.sendMessage(msg);
        },
        IOException.class,
        MessagingException.class);
  }
}
