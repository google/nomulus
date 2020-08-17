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

package google.registry.reporting.billing;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.gcs.GcsUtils;
import google.registry.util.EmailMessage;
import google.registry.util.EmailMessage.Attachment;
import google.registry.util.SendEmailService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link google.registry.reporting.billing.BillingEmailUtils}. */
class BillingEmailUtilsTest {

  private SendEmailService emailService;
  private BillingEmailUtils emailUtils;
  private GcsUtils gcsUtils;
  private ArgumentCaptor<EmailMessage> contentCaptor;

  @BeforeEach
  void beforeEach() throws Exception {
    emailService = mock(SendEmailService.class);
    gcsUtils = mock(GcsUtils.class);
    when(gcsUtils.openInputStream(new GcsFilename("test-bucket", "results/REG-INV-2017-10.csv")))
        .thenReturn(
            new ByteArrayInputStream("test,data\nhello,world".getBytes(StandardCharsets.UTF_8)));
    contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);

    emailUtils =
        new BillingEmailUtils(
            emailService,
            new YearMonth(2017, 10),
            new InternetAddress("my-sender@test.com"),
            new InternetAddress("my-receiver@test.com"),
            ImmutableList.of(
                new InternetAddress("hello@world.com"), new InternetAddress("hola@mundo.com")),
            "test-bucket",
            "REG-INV",
            "results/",
            gcsUtils);
  }

  @Test
  void testSuccess_emailOverallInvoice() throws MessagingException {
    emailUtils.emailOverallInvoice();

    verify(emailService).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    EmailMessage expectedContent =
        EmailMessage.newBuilder()
            .setFrom(new InternetAddress("my-sender@test.com"))
            .setRecipients(
                ImmutableList.of(
                    new InternetAddress("hello@world.com"), new InternetAddress("hola@mundo.com")))
            .setSubject("Domain Registry invoice data 2017-10")
            .setBody("Attached is the 2017-10 invoice for the domain registry.")
            .setAttachment(
                Attachment.newBuilder()
                    .setContent("test,data\nhello,world")
                    .setContentType(MediaType.CSV_UTF_8)
                    .setFilename("REG-INV-2017-10.csv")
                    .build())
            .build();
    assertThat(emailMessage).isEqualTo(expectedContent);
  }

  @Test
  void testFailure_emailsAlert() throws MessagingException {
    doThrow(new RuntimeException(new MessagingException("expected")))
        .doNothing()
        .when(emailService)
        .sendEmail(contentCaptor.capture());
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> emailUtils.emailOverallInvoice());
    assertThat(thrown).hasMessageThat().isEqualTo("Emailing invoice failed");
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("javax.mail.MessagingException: expected");
    // Verify we sent an e-mail alert
    verify(emailService, times(2)).sendEmail(contentCaptor.capture());
    validateAlertMessage(contentCaptor.getValue(), "Emailing invoice failed due to expected");
  }

  @Test
  void testSuccess_sendAlertEmail() throws MessagingException {
    emailUtils.sendAlertEmail("Alert!");
    verify(emailService).sendEmail(contentCaptor.capture());
    validateAlertMessage(contentCaptor.getValue(), "Alert!");
  }

  private void validateAlertMessage(EmailMessage emailMessage, String body)
      throws MessagingException {
    assertThat(emailMessage.from()).isEqualTo(new InternetAddress("my-sender@test.com"));
    assertThat(emailMessage.recipients())
        .containsExactly(new InternetAddress("my-receiver@test.com"));
    assertThat(emailMessage.subject()).isEqualTo("Billing Pipeline Alert: 2017-10");
    assertThat(emailMessage.contentType()).isEmpty();
    assertThat(emailMessage.body()).isEqualTo(body);
  }
}
