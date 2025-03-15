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

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.StorageException;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.groups.GmailClient;
import google.registry.reporting.billing.BillingModule.InvoiceDirectoryPrefix;
import google.registry.util.EmailMessage;
import jakarta.inject.Inject;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import org.joda.time.YearMonth;

/** Utility functions for sending emails involving monthly invoices. */
public class BillingEmailUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final GmailClient gmailClient;
  private final YearMonth yearMonth;
  private final InternetAddress alertRecipientAddress;
  private final ImmutableList<InternetAddress> invoiceEmailRecipients;
  private final Optional<InternetAddress> replyToEmailAddress;
  private final String billingBucket;
  private final String invoiceFilePrefix;
  private final String invoiceDirectoryPrefix;
  private final String billingInvoiceOriginUrl;
  private final GcsUtils gcsUtils;

  @Inject
  BillingEmailUtils(
      GmailClient gmailClient,
      YearMonth yearMonth,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("invoiceEmailRecipients") ImmutableList<InternetAddress> invoiceEmailRecipients,
      @Config("invoiceReplyToEmailAddress") Optional<InternetAddress> replyToEmailAddress,
      @Config("billingBucket") String billingBucket,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @Config("billingInvoiceOriginUrl") String billingInvoiceOriginUrl,
      @InvoiceDirectoryPrefix String invoiceDirectoryPrefix,
      GcsUtils gcsUtils) {
    this.gmailClient = gmailClient;
    this.yearMonth = yearMonth;
    this.alertRecipientAddress = alertRecipientAddress;
    this.invoiceEmailRecipients = invoiceEmailRecipients;
    this.replyToEmailAddress = replyToEmailAddress;
    this.billingBucket = billingBucket;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.invoiceDirectoryPrefix = invoiceDirectoryPrefix;
    this.billingInvoiceOriginUrl = billingInvoiceOriginUrl;
    this.gcsUtils = gcsUtils;
  }

  /** Sends an e-mail to all expected recipients with an attached overall invoice from GCS. */
  public void emailOverallInvoice() {
    try {
      String invoiceFile = String.format("%s-%s.csv", invoiceFilePrefix, yearMonth);
      String fileUrl = billingInvoiceOriginUrl + invoiceDirectoryPrefix + invoiceFile;
      BlobId invoiceFilename = BlobId.of(billingBucket, invoiceDirectoryPrefix + invoiceFile);
      try {
        gcsUtils.updateContentType(invoiceFilename, "text/csv");
      } catch (StorageException e) {
        // We want to continue with email anyway, it just will be less convenient for billing team
        // to process the file.
        logger.atWarning().withCause(e).log("Failed to update invoice file type");
      }
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setSubject(String.format("Domain Registry invoice data %s", yearMonth))
              .setBody(
                  String.format(
                      "<p>Use the following link to download %s invoice for the domain registry -"
                          + " <a href=\"%s\">invoice</a>.</p>",
                      yearMonth, fileUrl))
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
              .build());
    } catch (Throwable e) {
      throw new RuntimeException("The alert e-mail system failed.", e);
    }
  }
}
