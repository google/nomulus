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

package google.registry.ui.server;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import google.registry.groups.GmailClient;
import google.registry.util.EmailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link SendEmailUtils}. */
class SendEmailUtilsTest {

  private final GmailClient gmailClient = mock(GmailClient.class);
  private SendEmailUtils sendEmailUtils;

  private void setRecipients(ImmutableList<String> recipients) throws Exception {
    sendEmailUtils =
        new SendEmailUtils(
            recipients,
            gmailClient);
  }

  @Test
  void testSuccess_sendToOneAddress() throws Exception {
    setRecipients(ImmutableList.of("johnny@fakesite.tld"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isTrue();
    verifyMessageSent("johnny@fakesite.tld");
  }

  @Test
  void testSuccess_sendToMultipleAddresses() throws Exception {
    setRecipients(ImmutableList.of("foo@example.com", "bar@example.com"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isTrue();
    verifyMessageSent("foo@example.com", "bar@example.com");
  }

  @Test
  void testSuccess_ignoresMalformedEmailAddress() throws Exception {
    setRecipients(ImmutableList.of("foo@example.com", "1iñvalidemail"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isTrue();
    verifyMessageSent("foo@example.com");
  }

  @Test
  void testFailure_noAddresses() throws Exception {
    setRecipients(ImmutableList.of());
    assertThat(sendEmailUtils.hasRecipients()).isFalse();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isFalse();
    verify(gmailClient, never()).sendEmail(any());
  }

  @Test
  void testFailure_onlyGivenMalformedAddress() throws Exception {
    setRecipients(ImmutableList.of("1iñvalidemail"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isFalse();
    verify(gmailClient, never()).sendEmail(any());
  }

  @Test
  void testFailure_exceptionThrownDuringSend() throws Exception {
    setRecipients(ImmutableList.of("foo@example.com"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    doThrow(new RuntimeException(new MessagingException("expected")))
        .when(gmailClient)
        .sendEmail(any());
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place."))
        .isFalse();
    verifyMessageSent("foo@example.com");
  }

  @Test
  void testSuccess_bcc() throws Exception {
    setRecipients(ImmutableList.of("johnny@fakesite.tld"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    sendEmailUtils.sendEmail(
        "Welcome to the Internet",
        "It is a dark and scary place.",
        Optional.of("bar@example.com"),
        ImmutableList.of("baz@example.com"));

    ArgumentCaptor<EmailMessage> contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    ImmutableList.Builder<InternetAddress> recipientBuilder = ImmutableList.builder();
    for (String expectedRecipient : ImmutableList.of("johnny@fakesite.tld", "baz@example.com")) {
      recipientBuilder.add(new InternetAddress(expectedRecipient));
    }
    EmailMessage expectedContent =
        EmailMessage.newBuilder()
            .setSubject("Welcome to the Internet")
            .setBody("It is a dark and scary place.")
            .addBcc(new InternetAddress("bar@example.com"))
            .setRecipients(recipientBuilder.build())
            .build();
    assertThat(emailMessage).isEqualTo(expectedContent);
  }

  @Test
  void testAdditionalRecipients() throws Exception {
    setRecipients(ImmutableList.of("foo@example.com"));
    assertThat(sendEmailUtils.hasRecipients()).isTrue();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place.",
                ImmutableList.of("bar@example.com", "baz@example.com")))
        .isTrue();
    verifyMessageSent("foo@example.com", "bar@example.com", "baz@example.com");
  }

  @Test
  void testOnlyAdditionalRecipients() throws Exception {
    setRecipients(ImmutableList.of());
    assertThat(sendEmailUtils.hasRecipients()).isFalse();
    assertThat(
            sendEmailUtils.sendEmail(
                "Welcome to the Internet",
                "It is a dark and scary place.",
                ImmutableList.of("bar@example.com", "baz@example.com")))
        .isTrue();
    verifyMessageSent("bar@example.com", "baz@example.com");
  }

  private void verifyMessageSent(String... expectedRecipients) throws Exception {
    ArgumentCaptor<EmailMessage> contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    ImmutableList.Builder<InternetAddress> recipientBuilder = ImmutableList.builder();
    for (String expectedRecipient : expectedRecipients) {
      recipientBuilder.add(new InternetAddress(expectedRecipient));
    }
    EmailMessage expectedContent =
        EmailMessage.newBuilder()
            .setSubject("Welcome to the Internet")
            .setBody("It is a dark and scary place.")
            .setRecipients(recipientBuilder.build())
            .build();
    assertThat(emailMessage).isEqualTo(expectedContent);
  }
}
