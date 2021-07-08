// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.EmailMessage.Attachment;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SendEmailService}. */
@ExtendWith(MockitoExtension.class)
class SendEmailServiceTest {

  private final Retrier retrier = new Retrier(new FakeSleeper(new FakeClock()), 2);
  private final TransportEmailSender wrapper = mock(TransportEmailSender.class);
  private final SendEmailService sendEmailService = new SendEmailService(retrier, wrapper);

  @Captor private ArgumentCaptor<Message> messageCaptor;

  @Test
  void testSuccess_simple() throws Exception {
    EmailMessage content = createBuilder().build();
    sendEmailService.sendEmail(content);
    Message message = getMessage();
    assertThat(message.getAllRecipients())
        .asList()
        .containsExactly(new InternetAddress("fake@example.com"));
    assertThat(message.getFrom())
        .asList()
        .containsExactly(new InternetAddress("registry@example.com"));
    assertThat(message.getRecipients(RecipientType.BCC)).isNull();
    assertThat(message.getSubject()).isEqualTo("Subject");
    assertThat(message.getContentType()).startsWith("multipart/mixed");
    assertThat(getInternalContent(message).getContent().toString()).isEqualTo("body");
    assertThat(getInternalContent(message).getContentType()).isEqualTo("text/plain; charset=utf-8");
    assertThat(((MimeMultipart) message.getContent()).getCount()).isEqualTo(1);
  }

  @Test
  void testSuccess_addBccs() throws Exception {
    EmailMessage.Builder contentBuilder =
        EmailMessage.newBuilder()
            .setFrom(new InternetAddress("test@example.com"))
            .setSubject("test subject")
            .setBody("test body");
    for (String email :
        ImmutableList.of("bcc@example.com", "bcc1@example.com", "bcc2@example.com")) {
      contentBuilder.addBcc(new InternetAddress(email));
    }

    sendEmailService.sendEmail(contentBuilder.build());
    assertThat(getMessage().getRecipients(RecipientType.BCC))
        .asList()
        .containsExactly(
            new InternetAddress("bcc@example.com"),
            new InternetAddress("bcc1@example.com"),
            new InternetAddress("bcc2@example.com"));
  }

  @Test
  void testSuccess_setBccs() throws Exception {
    EmailMessage content =
        createBuilder()
            .setBccs(
                ImmutableList.of(
                    new InternetAddress("bcc@example.com"),
                    new InternetAddress("bcc2@example.com")))
            .build();
    sendEmailService.sendEmail(content);
    assertThat(getMessage().getRecipients(RecipientType.BCC))
        .asList()
        .containsExactly(
            new InternetAddress("bcc@example.com"), new InternetAddress("bcc2@example.com"));
  }

  @Test
  void testSuccess_emptyBcc() throws Exception {
    sendEmailService.sendEmail(createBuilder().setBccs(ImmutableList.of()).build());
    assertThat(getMessage().getRecipients(RecipientType.BCC)).isNull();
  }

  @Test
  void testSuccess_addCcs() throws Exception {
    EmailMessage.Builder contentBuilder = EmailMessage.newBuilder();
    contentBuilder
        .setFrom(new InternetAddress("test@example.com"))
        .setSubject("test subject")
        .setBody("test body");
    for (String email : ImmutableList.of("cc@example.com", "cc1@example.com", "cc2@example.com")) {
      contentBuilder.addCc(new InternetAddress(email));
    }
    sendEmailService.sendEmail(contentBuilder.build());
    assertThat(getMessage().getRecipients(RecipientType.CC))
        .asList()
        .containsExactly(
            new InternetAddress("cc@example.com"),
            new InternetAddress("cc1@example.com"),
            new InternetAddress("cc2@example.com"));
  }

  @Test
  void testSuccess_setCcs() throws Exception {
    EmailMessage content =
        createBuilder()
            .setCcs(
                ImmutableList.of(
                    new InternetAddress("cc@example.com"), new InternetAddress("cc2@example.com")))
            .build();
    sendEmailService.sendEmail(content);
    assertThat(getMessage().getRecipients(RecipientType.CC))
        .asList()
        .containsExactly(
            new InternetAddress("cc@example.com"), new InternetAddress("cc2@example.com"));
  }

  @Test
  void testSuccess_emptyCC() throws Exception {
    sendEmailService.sendEmail(createBuilder().setCcs(ImmutableList.of()).build());
    assertThat(getMessage().getRecipients(RecipientType.CC)).isNull();
  }

  @Test
  void testSuccess_contentType() throws Exception {
    sendEmailService.sendEmail(createBuilder().setContentType(MediaType.HTML_UTF_8).build());
    assertThat(getInternalContent(getMessage()).getContentType())
        .isEqualTo("text/html; charset=utf-8");
  }

  @Test
  void testSuccess_attachment() throws Exception {
    EmailMessage content =
        createBuilder()
            .setAttachment(
                Attachment.newBuilder()
                    .setFilename("filename")
                    .setContent("foo,bar\nbaz,qux")
                    .setContentType(MediaType.CSV_UTF_8)
                    .build())
            .build();
    sendEmailService.sendEmail(content);
    Message message = getMessage();
    assertThat(((MimeMultipart) message.getContent()).getCount()).isEqualTo(2);
    BodyPart attachment = ((MimeMultipart) message.getContent()).getBodyPart(1);
    assertThat(attachment.getContent()).isEqualTo("foo,bar\nbaz,qux");
    assertThat(attachment.getContentType()).endsWith("name=filename");
  }

  @Test
  void testSuccess_retry() throws Exception {
    doThrow(new MessagingException("hi"))
        .doNothing()
        .when(wrapper)
        .sendMessage(messageCaptor.capture());
    sendEmailService.sendEmail(createBuilder().build());
    assertThat(messageCaptor.getValue().getSubject()).isEqualTo("Subject");
  }

  @Test
  void testFailure_wrongExceptionType() throws Exception {
    doThrow(new RuntimeException("this is a runtime exception")).when(wrapper).sendMessage(any());
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> sendEmailService.sendEmail(createBuilder().build()));
    assertThat(thrown).hasMessageThat().isEqualTo("this is a runtime exception");
  }

  @Test
  void testFailure_tooManyTries() throws Exception {
    doThrow(new MessagingException("hi"))
        .doThrow(new MessagingException("second"))
        .when(wrapper)
        .sendMessage(any());
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> sendEmailService.sendEmail(createBuilder().build()));
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("second");
    assertThat(thrown).hasCauseThat().isInstanceOf(MessagingException.class);
  }

  private EmailMessage.Builder createBuilder() throws Exception {
    return EmailMessage.newBuilder()
        .setFrom(new InternetAddress("registry@example.com"))
        .addRecipient(new InternetAddress("fake@example.com"))
        .setSubject("Subject")
        .setBody("body");
  }

  private Message getMessage() throws MessagingException {
    verify(wrapper).sendMessage(messageCaptor.capture());
    return messageCaptor.getValue();
  }

  private BodyPart getInternalContent(Message message) throws Exception {
    return ((MimeMultipart) message.getContent()).getBodyPart(0);
  }
}
