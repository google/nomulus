// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.invoicing;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.reporting.billing.BillingModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.regex.Pattern;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * A POJO representing a single billable event, parsed from a {@code SchemaAndRecord}.
 *
 * <p>This is a trivially serializable class that allows Beam to transform the results of a Cloud
 * SQL query into a standard Java representation, giving us the type guarantees and ease of
 * manipulation Cloud SQL lacks.
 */
@AutoValue
public abstract class BillingEvent implements Serializable {

  private static final long serialVersionUID = -3593088371541450077L;

  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss zzz");

  private static final Pattern SYNTHETIC_REGEX = Pattern.compile("SYNTHETIC", Pattern.LITERAL);

  private static final ImmutableList<String> FIELD_NAMES =
      ImmutableList.of(
          "id",
          "billingTime",
          "eventTime",
          "registrarId",
          "billingId",
          "poNumber",
          "tld",
          "action",
          "domain",
          "repositoryId",
          "years",
          "currency",
          "amount",
          "flags");

  /** Returns the unique Objectify ID for the {@code OneTime} associated with this event. */
  abstract long id();

  /** Returns the UTC DateTime this event becomes billable. */
  abstract DateTime billingTime();

  /** Returns the UTC DateTime this event was generated. */
  abstract DateTime eventTime();

  /** Returns the billed registrar's name. */
  abstract String registrarId();

  /** Returns the billed registrar's billing account key. */
  abstract String billingId();

  /** Returns the Purchase Order number. */
  abstract String poNumber();

  /** Returns the tld this event was generated for. */
  abstract String tld();

  /** Returns the billable action this event was generated for (i.e. RENEW, CREATE, TRANSFER...) */
  abstract String action();

  /** Returns the fully qualified domain name this event was generated for. */
  abstract String domain();

  /** Returns the unique RepoID associated with the billed domain. */
  abstract String repositoryId();

  /** Returns the number of years this billing event is made out for. */
  abstract int years();

  /** Returns the 3-letter currency code for the billing event (i.e. USD or JPY.) */
  abstract String currency();

  /** Returns the cost associated with this billing event. */
  abstract double amount();

  /** Returns a list of space-delimited flags associated with the event. */
  abstract String flags();

  /** Creates a concrete {@link BillingEvent}. */
  static BillingEvent create(
      long id,
      DateTime billingTime,
      DateTime eventTime,
      String registrarId,
      String billingId,
      String poNumber,
      String tld,
      String action,
      String domain,
      String repositoryId,
      int years,
      String currency,
      double amount,
      String flags) {
    return new AutoValue_BillingEvent(
        id,
        billingTime,
        eventTime,
        registrarId,
        billingId,
        poNumber,
        tld,
        action,
        domain,
        repositoryId,
        years,
        currency,
        amount,
        flags);
  }

  static String getHeader() {
    return String.join(",", FIELD_NAMES);
  }

  /**
   * Generates the filename associated with this {@code BillingEvent}.
   *
   * <p>When modifying this function, take care to ensure that there's no way to generate an illegal
   * filepath with the arguments, such as "../sensitive_info".
   */
  String toFilename(String yearMonth) {
    return String.format(
        "%s_%s_%s_%s", BillingModule.DETAIL_REPORT_PREFIX, yearMonth, registrarId(), tld());
  }

  /** Generates a CSV representation of this {@code BillingEvent}. */
  String toCsv() {
    return Joiner.on(",")
        .join(
            ImmutableList.of(
                id(),
                DATE_TIME_FORMATTER.print(billingTime()),
                DATE_TIME_FORMATTER.print(eventTime()),
                registrarId(),
                billingId(),
                poNumber(),
                tld(),
                action(),
                domain(),
                repositoryId(),
                years(),
                currency(),
                String.format("%.2f", amount()),
                // Strip out the 'synthetic' flag, which is internal only.
                SYNTHETIC_REGEX.matcher(flags()).replaceAll("").trim()));
  }

  /** Returns the grouping key for this {@code BillingEvent}, to generate the overall invoice. */
  InvoiceGroupingKey getInvoiceGroupingKey() {
    return new AutoValue_BillingEvent_InvoiceGroupingKey(
        billingTime().toLocalDate().withDayOfMonth(1).toString(),
        years() == 0
            ? ""
            : billingTime()
                .toLocalDate()
                .withDayOfMonth(1)
                .plusYears(years())
                .minusDays(1)
                .toString(),
        billingId(),
        String.format("%s - %s", registrarId(), tld()),
        String.format("%s | TLD: %s | TERM: %d-year", action(), tld(), years()),
        amount(),
        currency(),
        poNumber());
  }

  /** Returns the grouping key for this {@code BillingEvent}, to generate the detailed report. */
  String getDetailedReportGroupingKey() {
    return String.format("%s_%s", registrarId(), tld());
  }

  /** Key for each {@code BillingEvent}, when aggregating for the overall invoice. */
  @AutoValue
  abstract static class InvoiceGroupingKey implements Serializable {

    private static final long serialVersionUID = -151561764235256205L;

    private static final ImmutableList<String> INVOICE_HEADERS =
        ImmutableList.of(
            "StartDate",
            "EndDate",
            "ProductAccountKey",
            "Amount",
            "AmountCurrency",
            "BillingProductCode",
            "SalesChannel",
            "LineItemType",
            "UsageGroupingKey",
            "Quantity",
            "Description",
            "UnitPrice",
            "UnitPriceCurrency",
            "PONumber");

    /** Returns the first day this invoice is valid, in yyyy-MM-dd format. */
    abstract String startDate();

    /** Returns the last day this invoice is valid, in yyyy-MM-dd format. */
    abstract String endDate();

    /** Returns the billing account id, which is the {@code BillingEvent.billingId}. */
    abstract String productAccountKey();

    /** Returns the invoice grouping key, which is in the format "registrarId - tld". */
    abstract String usageGroupingKey();

    /** Returns a description of the item, formatted as "action | TLD: tld | TERM: n-year." */
    abstract String description();

    /** Returns the cost per invoice item. */
    abstract Double unitPrice();

    /** Returns the 3-digit currency code the unit price uses. */
    abstract String unitPriceCurrency();

    /** Returns the purchase order number for the item, blank for most registrars. */
    abstract String poNumber();

    /** Generates the CSV header for the overall invoice. */
    static String invoiceHeader() {
      return Joiner.on(",").join(INVOICE_HEADERS);
    }

    /** Generates a CSV representation of n aggregate billing events. */
    String toCsv(Long quantity) {
      double totalPrice = unitPrice() * quantity;
      return Joiner.on(",")
          .join(
              ImmutableList.of(
                  startDate(),
                  endDate(),
                  productAccountKey(),
                  String.format("%.2f", totalPrice),
                  unitPriceCurrency(),
                  "10125",
                  "1",
                  "PURCHASE",
                  usageGroupingKey(),
                  String.format("%d", quantity),
                  description(),
                  String.format("%.2f", unitPrice()),
                  unitPriceCurrency(),
                  poNumber()));
    }

    /** Coder that provides deterministic (de)serialization for {@code InvoiceGroupingKey}. */
    static class InvoiceGroupingKeyCoder extends AtomicCoder<InvoiceGroupingKey> {

      private static final long serialVersionUID = 6680701524304107547L;

      @Override
      public void encode(InvoiceGroupingKey value, OutputStream outStream) throws IOException {
        Coder<String> stringCoder = StringUtf8Coder.of();
        stringCoder.encode(value.startDate(), outStream);
        stringCoder.encode(value.endDate(), outStream);
        stringCoder.encode(value.productAccountKey(), outStream);
        stringCoder.encode(value.usageGroupingKey(), outStream);
        stringCoder.encode(value.description(), outStream);
        stringCoder.encode(String.valueOf(value.unitPrice()), outStream);
        stringCoder.encode(value.unitPriceCurrency(), outStream);
        stringCoder.encode(value.poNumber(), outStream);
      }

      @Override
      public InvoiceGroupingKey decode(InputStream inStream) throws IOException {
        Coder<String> stringCoder = StringUtf8Coder.of();
        return new AutoValue_BillingEvent_InvoiceGroupingKey(
            stringCoder.decode(inStream),
            stringCoder.decode(inStream),
            stringCoder.decode(inStream),
            stringCoder.decode(inStream),
            stringCoder.decode(inStream),
            Double.parseDouble(stringCoder.decode(inStream)),
            stringCoder.decode(inStream),
            stringCoder.decode(inStream));
      }
    }
  }
}
