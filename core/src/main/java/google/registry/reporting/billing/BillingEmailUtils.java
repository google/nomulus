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

import static com.google.common.base.Throwables.getRootCause;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.reporting.billing.BillingModule.InvoiceDirectoryPrefix;
import google.registry.util.EmailMessage;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import org.joda.time.YearMonth;

/** Utility functions for sending emails involving monthly invoices. */
public class BillingEmailUtils {

  private final GmailClient gmailClient;
  private final YearMonth yearMonth;
  private final InternetAddress outgoingEmailAddress;
  private final InternetAddress alertRecipientAddress;
  private final ImmutableList<InternetAddress> invoiceEmailRecipients;
  private final Optional<InternetAddress> replyToEmailAddress;
  private final String invoiceFilePrefix;
  private final String invoiceDirectoryPrefix;
  private final String billingInvoiceOriginUrl;

  @Inject
  BillingEmailUtils(
      GmailClient gmailClient,
      YearMonth yearMonth,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress outgoingEmailAddress,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("invoiceEmailRecipients") ImmutableList<InternetAddress> invoiceEmailRecipients,
      @Config("invoiceReplyToEmailAddress") Optional<InternetAddress> replyToEmailAddress,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @Config("billingInvoiceOriginUrl") String billingInvoiceOriginUrl,
      @InvoiceDirectoryPrefix String invoiceDirectoryPrefix) {
    this.gmailClient = gmailClient;
    this.yearMonth = yearMonth;
    this.outgoingEmailAddress = outgoingEmailAddress;
    this.alertRecipientAddress = alertRecipientAddress;
    this.invoiceEmailRecipients = invoiceEmailRecipients;
    this.replyToEmailAddress = replyToEmailAddress;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.invoiceDirectoryPrefix = invoiceDirectoryPrefix;
    this.billingInvoiceOriginUrl = billingInvoiceOriginUrl;
  }

  /** Sends an e-mail to all expected recipients with an attached overall invoice from GCS. */
  public void emailOverallInvoice() {
    try {
      String invoiceFile = String.format("%s-%s.csv", invoiceFilePrefix, yearMonth);
      String fileUrl = billingInvoiceOriginUrl + invoiceDirectoryPrefix + invoiceFile;
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setSubject(String.format("Domain Registry invoice data %s", yearMonth))
              .setBody(
                  String.format(
                      "<p>Use the following link to download %s invoice for the domain registry -"
                          + " <a href=\"%s\">invoice</a>.</p>",
                      yearMonth, fileUrl))
              .setFrom(outgoingEmailAddress)
              .setRecipients(invoiceEmailRecipients)
              .setReplyToEmailAddress(replyToEmailAddress)
              .setContentType(MediaType.HTML_UTF_8)
              .build());
    } catch (Throwable e) {
      // Strip one layer, because callWithRetry wraps in a RuntimeException
      sendAlertEmail(
          String.format("Emailing invoice failed due to %s", getRootCause(e).getMessage()));
      throw new RuntimeException("Emailing invoice failed", e);
    }
  }

  /** Sends an e-mail to the provided alert e-mail address indicating a billing failure. */
  void sendAlertEmail(String body) {
    try {
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setSubject(String.format("Billing Pipeline Alert: %s", yearMonth))
              .setBody(body)
              .addRecipient(alertRecipientAddress)
              .setFrom(outgoingEmailAddress)
              .build());
    } catch (Throwable e) {
      throw new RuntimeException("The alert e-mail system failed.", e);
    }
  }
}
