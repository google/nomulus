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

import static com.google.common.net.MediaType.CSV_UTF_8;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import google.registry.groups.GmailClient.RetriableGmailExceptionPredicate;
import google.registry.util.EmailMessage;
import google.registry.util.EmailMessage.Attachment;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.io.OutputStream;
import javax.mail.Message.RecipientType;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

/** Unit tests for {@link GmailClient}. */
@ExtendWith(MockitoExtension.class)
public class GmailClientTest {

  @Mock private Gmail gmail;
  @Mock private HttpResponseException httpResponseException;
  private GmailClient gmailClient;

  @BeforeEach
  public void setup() throws Exception {
    gmailClient =
        new GmailClient(
            gmail,
            new Retrier(new SystemSleeper(), 3),
            "from@example.com",
            "My sender",
            new InternetAddress("replyTo@example.com"));
  }

  @Test
  public void toMimeMessage_fullMessage() throws Exception {
    InternetAddress fromAddr = new InternetAddress("from@example.com", "My sender");
    InternetAddress toAddr = new InternetAddress("to@example.com");
    InternetAddress ccAddr = new InternetAddress("cc@example.com");
    InternetAddress bccAddr = new InternetAddress("bcc@example.com");
    EmailMessage emailMessage =
        EmailMessage.newBuilder()
            .setFrom(fromAddr)
            .setRecipients(ImmutableList.of(toAddr))
            .setSubject("My subject")
            .setBody("My body")
            .addCc(ccAddr)
            .addBcc(bccAddr)
            .setAttachment(
                Attachment.newBuilder()
                    .setFilename("filename")
                    .setContent("foo,bar\nbaz,qux")
                    .setContentType(CSV_UTF_8)
                    .build())
            .build();
    MimeMessage mimeMessage = gmailClient.toMimeMessage(emailMessage);
    assertThat(mimeMessage.getFrom()).asList().containsExactly(fromAddr);
    assertThat(mimeMessage.getRecipients(RecipientType.TO)).asList().containsExactly(toAddr);
    assertThat(mimeMessage.getRecipients(RecipientType.CC)).asList().containsExactly(ccAddr);
    assertThat(mimeMessage.getRecipients(RecipientType.BCC)).asList().containsExactly(bccAddr);
    assertThat(mimeMessage.getSubject()).isEqualTo("My subject");
    assertThat(mimeMessage.getContent()).isInstanceOf(MimeMultipart.class);
    MimeMultipart parts = (MimeMultipart) mimeMessage.getContent();
    Part body = parts.getBodyPart(0);
    assertThat(body.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8.toString());
    assertThat(body.getContent()).isEqualTo("My body");
    Part attachment = parts.getBodyPart(1);
    assertThat(attachment.getContentType()).startsWith(CSV_UTF_8.toString());
    assertThat(attachment.getContentType()).endsWith("name=filename");
    assertThat(attachment.getContent()).isEqualTo("foo,bar\nbaz,qux");
  }

  @Test
  public void toGmailMessage() throws Exception {
    MimeMessage mimeMessage = mock(MimeMessage.class);
    byte[] data = "My content".getBytes(UTF_8);
    doAnswer(
            new Answer() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                OutputStream os = invocation.getArgument(0);
                os.write(data);
                return null;
              }
            })
        .when(mimeMessage)
        .writeTo(any(OutputStream.class));
    Message gmailMessage = GmailClient.toGmailMessage(mimeMessage);
    assertThat(gmailMessage.decodeRaw()).isEqualTo(data);
  }

  @Test
  public void isRetriable_trueIfNotHttpResponseException() {
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(new Exception())).isTrue();
  }

  @Test
  public void isHttpResponseExceptionRetriable_trueIf500() {
    when(httpResponseException.getStatusCode()).thenReturn(500);
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isTrue();
  }

  @Test
  public void isHttpResponseExceptionRetriable_trueIf429() {
    when(httpResponseException.getStatusCode()).thenReturn(429);
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isTrue();
  }

  @Test
  public void isHttpResponseExceptionRetriable_trueIf403WithRateLimitOverage() {
    when(httpResponseException.getStatusCode()).thenReturn(403);
    when(httpResponseException.getStatusMessage()).thenReturn("rateLimitExceeded");
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isTrue();
  }

  @Test
  public void isHttpResponseExceptionRetriable_trueIf403WithUserRateLimitOverage() {
    when(httpResponseException.getStatusCode()).thenReturn(403);
    when(httpResponseException.getStatusMessage()).thenReturn("userRateLimitExceeded");
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isTrue();
  }

  @Test
  public void isHttpResponseExceptionRetriable_falseIf403WithLongLastingOverage() {
    when(httpResponseException.getStatusCode()).thenReturn(403);
    when(httpResponseException.getStatusMessage()).thenReturn("dailyLimitExceeded");
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isFalse();
  }

  @Test
  public void isHttpResponseExceptionRetriable_falseIfBadRequest() {
    when(httpResponseException.getStatusCode()).thenReturn(400);
    assertThat(RetriableGmailExceptionPredicate.INSTANCE.test(httpResponseException)).isFalse();
  }
}
