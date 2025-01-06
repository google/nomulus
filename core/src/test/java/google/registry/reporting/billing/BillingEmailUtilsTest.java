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

package google.registry.reporting.billing;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.gcs.GcsUtils;
import google.registry.groups.GmailClient;
import google.registry.util.EmailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link google.registry.reporting.billing.BillingEmailUtils}. */
class BillingEmailUtilsTest {

  private GmailClient gmailClient;
  private BillingEmailUtils emailUtils;
  private ArgumentCaptor<EmailMessage> contentCaptor;
  private GcsUtils gcsUtils;

  @BeforeEach
  void beforeEach() throws Exception {
    gmailClient = mock(GmailClient.class);
    contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    gcsUtils = mock(GcsUtils.class);
    emailUtils = getEmailUtils(Optional.of(new InternetAddress("reply-to@test.com")));
  }

  private BillingEmailUtils getEmailUtils(Optional<InternetAddress> replyToAddress)
      throws Exception {
    return new BillingEmailUtils(
        gmailClient,
        new YearMonth(2017, 10),
        new InternetAddress("my-receiver@test.com"),
        ImmutableList.of(
            new InternetAddress("hello@world.com"), new InternetAddress("hola@mundo.com")),
        replyToAddress,
        "test-bucket",
        "REG-INV",
        "www.google.com/",
        "results/",
        gcsUtils);
  }

  @Test
  void testSuccess_emailOverallInvoice() throws MessagingException {
    emailUtils.emailOverallInvoice();

    verify(gmailClient).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    EmailMessage expectedContent =
        EmailMessage.newBuilder()
            .setRecipients(
                ImmutableList.of(
                    new InternetAddress("hello@world.com"), new InternetAddress("hola@mundo.com")))
            .setSubject("Domain Registry invoice data 2017-10")
            .setBody(
                "<p>Use the following link to download 2017-10 invoice for the domain registry -"
                    + " <a href=\"www.google.com/results/REG-INV-2017-10.csv\">invoice</a>.</p>")
            .setReplyToEmailAddress(new InternetAddress("reply-to@test.com"))
            .setContentType(MediaType.HTML_UTF_8)
            .build();
    assertThat(emailMessage).isEqualTo(expectedContent);
  }

  @Test
  void testSuccess_emailOverallInvoiceNoReplyOverride() throws Exception {
    emailUtils = getEmailUtils(Optional.empty());
    emailUtils.emailOverallInvoice();

    verify(gmailClient).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    assertThat(emailMessage.replyToEmailAddress()).isEmpty();
  }

  @Test
  void testFailure_emailsAlert() throws MessagingException {
    doThrow(new RuntimeException(new MessagingException("expected")))
        .doNothing()
        .when(gmailClient)
        .sendEmail(contentCaptor.capture());
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> emailUtils.emailOverallInvoice());
    assertThat(thrown).hasMessageThat().isEqualTo("Emailing invoice failed");
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("jakarta.mail.MessagingException: expected");
    // Verify we sent an e-mail alert
    verify(gmailClient, times(2)).sendEmail(contentCaptor.capture());
    validateAlertMessage(contentCaptor.getValue(), "Emailing invoice failed due to expected");
  }

  @Test
  void testSuccess_sendAlertEmail() throws MessagingException {
    emailUtils.sendAlertEmail("Alert!");
    verify(gmailClient).sendEmail(contentCaptor.capture());
    validateAlertMessage(contentCaptor.getValue(), "Alert!");
  }

  private void validateAlertMessage(EmailMessage emailMessage, String body)
      throws MessagingException {
    assertThat(emailMessage.recipients())
        .containsExactly(new InternetAddress("my-receiver@test.com"));
    assertThat(emailMessage.subject()).isEqualTo("Billing Pipeline Alert: 2017-10");
    assertThat(emailMessage.contentType()).isEmpty();
    assertThat(emailMessage.body()).isEqualTo(body);
  }
}
