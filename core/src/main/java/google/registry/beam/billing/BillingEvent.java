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

package google.registry.beam.billing;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.reporting.billing.BillingModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * A record representing a single billable event, parsed from a {@code SchemaAndRecord}.
 *
 * @param id The unique ID for the {@code BillingEvent} associated with this event.
 * @param billingTime The DateTime (in UTC) this event becomes billable.
 * @param eventTime The DateTime (in UTC) this event was generated.
 * @param registrarId The billed registrar's name.
 * @param billingId The billed registrar's billing account key.
 * @param poNumber The Purchase Order number.
 * @param tld The TLD this event was generated for.
 * @param action The billable action this event was generated for (CREATE, RENEW, TRANSFER...).
 * @param domain The fully qualified domain name this event was generated for.
 * @param repositoryId The unique RepoID associated with the billed domain.
 * @param years The number of years this billing event is made out for.
 * @param currency The 3-letter currency code for the billing event (USD or JPY).
 * @param amount The total cost associated with this billing event.
 * @param flags A list of space-delimited flags associated with the event.
 */
public record BillingEvent(
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
    return new BillingEvent(
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
    return new InvoiceGroupingKey(
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
        registrarId(),
        String.format("%s | TLD: %s | TERM: %d-year", action(), tld(), years()),
        amount(),
        currency(),
        poNumber());
  }

  /** Returns the grouping key for this {@code BillingEvent}, to generate the detailed report. */
  String getDetailedReportGroupingKey() {
    return String.format("%s_%s", registrarId(), tld());
  }

  /**
   * Key for each {@code BillingEvent}, when aggregating for the overall invoice.
   *
   * @param startDate The first day this invoice is valid, in yyyy-MM-dd format.
   * @param endDate The last day this invoice is valid, in yyyy-MM-dd format.
   * @param productAccountKey The billing account id, which is the {@code BillingEvent.billingId}.
   * @param usageGroupingKey The invoice grouping key, which is the registrar ID.
   * @param description The description of the item, formatted as: {@code action | TLD: tld | TERM:
   *     n-year}.
   * @param unitPrice The cost per invoice item.
   * @param unitPriceCurrency The 3-digit currency code the unit price uses.
   * @param poNumber The purchase order number for the item, blank for most registrars.
   */
  record InvoiceGroupingKey(
      String startDate,
      String endDate,
      String productAccountKey,
      String usageGroupingKey,
      String description,
      Double unitPrice,
      String unitPriceCurrency,
      String poNumber) {

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
      private static final Coder<String> stringCoder = StringUtf8Coder.of();
      private static final InvoiceGroupingKeyCoder INSTANCE = new InvoiceGroupingKeyCoder();

      public static InvoiceGroupingKeyCoder of() {
        return INSTANCE;
      }

      private InvoiceGroupingKeyCoder() {}

      @Override
      public void encode(InvoiceGroupingKey value, @NotNull OutputStream outStream)
          throws IOException {
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
      public InvoiceGroupingKey decode(@NotNull InputStream inStream) throws IOException {
        return new InvoiceGroupingKey(
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

  static class BillingEventCoder extends AtomicCoder<BillingEvent> {
    private static final Coder<String> stringCoder = StringUtf8Coder.of();
    private static final Coder<Integer> integerCoder = VarIntCoder.of();
    private static final Coder<Long> longCoder = VarLongCoder.of();
    private static final Coder<Double> doubleCoder = DoubleCoder.of();
    private static final BillingEventCoder INSTANCE = new BillingEventCoder();

    static NullableCoder<BillingEvent> ofNullable() {
      return NullableCoder.of(INSTANCE);
    }

    private BillingEventCoder() {}

    @Override
    public void encode(BillingEvent value, OutputStream outStream) throws IOException {
      longCoder.encode(value.id(), outStream);
      stringCoder.encode(DATE_TIME_FORMATTER.print(value.billingTime()), outStream);
      stringCoder.encode(DATE_TIME_FORMATTER.print(value.eventTime()), outStream);
      stringCoder.encode(value.registrarId(), outStream);
      stringCoder.encode(value.billingId(), outStream);
      stringCoder.encode(value.poNumber(), outStream);
      stringCoder.encode(value.tld(), outStream);
      stringCoder.encode(value.action(), outStream);
      stringCoder.encode(value.domain(), outStream);
      stringCoder.encode(value.repositoryId(), outStream);
      integerCoder.encode(value.years(), outStream);
      stringCoder.encode(value.currency(), outStream);
      doubleCoder.encode(value.amount(), outStream);
      stringCoder.encode(value.flags(), outStream);
    }

    @Override
    public BillingEvent decode(InputStream inStream) throws IOException {
      return new BillingEvent(
          longCoder.decode(inStream),
          DATE_TIME_FORMATTER.parseDateTime(stringCoder.decode(inStream)),
          DATE_TIME_FORMATTER.parseDateTime(stringCoder.decode(inStream)),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          stringCoder.decode(inStream),
          integerCoder.decode(inStream),
          stringCoder.decode(inStream),
          doubleCoder.decode(inStream),
          stringCoder.decode(inStream));
    }
  }
}
