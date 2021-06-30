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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.storage.BlobId;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.reporting.billing.BillingModule.InvoiceDirectoryPrefix;
import google.registry.util.EmailMessage;
import google.registry.util.EmailMessage.Attachment;
import google.registry.util.SendEmailService;
import java.io.InputStream;
import java.io.InputStreamReader;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import org.joda.time.YearMonth;

/** Utility functions for sending emails involving monthly invoices. */
public class BillingEmailUtils {

  private final SendEmailService emailService;
  private final YearMonth yearMonth;
  private final InternetAddress outgoingEmailAddress;
  private final InternetAddress alertRecipientAddress;
  private final ImmutableList<InternetAddress> invoiceEmailRecipients;
  private final String billingBucket;
  private final String invoiceFilePrefix;
  private final String invoiceDirectoryPrefix;
  private final GcsUtils gcsUtils;

  @Inject
  BillingEmailUtils(
      SendEmailService emailService,
      YearMonth yearMonth,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress outgoingEmailAddress,
      @Config("alertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("invoiceEmailRecipients") ImmutableList<InternetAddress> invoiceEmailRecipients,
      @Config("billingBucket") String billingBucket,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @InvoiceDirectoryPrefix String invoiceDirectoryPrefix,
      GcsUtils gcsUtils) {
    this.emailService = emailService;
    this.yearMonth = yearMonth;
    this.outgoingEmailAddress = outgoingEmailAddress;
    this.alertRecipientAddress = alertRecipientAddress;
    this.invoiceEmailRecipients = invoiceEmailRecipients;
    this.billingBucket = billingBucket;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.invoiceDirectoryPrefix = invoiceDirectoryPrefix;
    this.gcsUtils = gcsUtils;
  }

  /** Sends an e-mail to all expected recipients with an attached overall invoice from GCS. */
  void emailOverallInvoice() {
    try {
      String invoiceFile = String.format("%s-%s.csv", invoiceFilePrefix, yearMonth);
      BlobId invoiceFilename = BlobId.of(billingBucket, invoiceDirectoryPrefix + invoiceFile);
      try (InputStream in = gcsUtils.openInputStream(invoiceFilename)) {
        emailService.sendEmail(
            EmailMessage.newBuilder()
                .setSubject(String.format("Domain Registry invoice data %s", yearMonth))
                .setBody(
                    String.format("Attached is the %s invoice for the domain registry.", yearMonth))
                .setFrom(outgoingEmailAddress)
                .setRecipients(invoiceEmailRecipients)
                .setAttachment(
                    Attachment.newBuilder()
                        .setContent(CharStreams.toString(new InputStreamReader(in, UTF_8)))
                        .setContentType(MediaType.CSV_UTF_8)
                        .setFilename(invoiceFile)
                        .build())
                .build());
      }
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
      emailService.sendEmail(
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
