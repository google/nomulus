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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import com.google.common.flogger.FluentLogger;
import google.registry.beam.billing.BillingEvent.BillingEventCoder;
import google.registry.beam.billing.BillingEvent.InvoiceGroupingKey;
import google.registry.beam.billing.BillingEvent.InvoiceGroupingKey.InvoiceGroupingKeyCoder;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingEvent;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.reporting.billing.BillingModule;
import google.registry.util.DomainNameUtils;
import google.registry.util.ResourceUtils;
import google.registry.util.SqlTemplate;
import java.io.Serial;
import java.io.Serializable;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Contextful;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.money.CurrencyUnit;

/**
 * Definition of a Dataflow Flex pipeline template, which generates a given month's invoices.
 *
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha
 * --pipeline=invoicing}.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
public class InvoicingPipeline implements Serializable {

  @Serial private static final long serialVersionUID = 5386330443625580081L;

  private static final Pattern SQL_COMMENT_REGEX =
      Pattern.compile("^\\s*--.*\\n", Pattern.MULTILINE);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final InvoicingPipelineOptions options;

  InvoicingPipeline(InvoicingPipelineOptions options) {
    this.options = options;
  }

  PipelineResult run() {
    Pipeline pipeline = Pipeline.create(options);
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    PCollection<google.registry.beam.billing.BillingEvent> billingEvents =
        readFromCloudSql(options, pipeline);
    saveInvoiceCsv(billingEvents, options);
    saveDetailedCsv(billingEvents, options);
  }

  static PCollection<google.registry.beam.billing.BillingEvent> readFromCloudSql(
      InvoicingPipelineOptions options, Pipeline pipeline) {
    Read<Object[], google.registry.beam.billing.BillingEvent> read =
        RegistryJpaIO.<Object[], google.registry.beam.billing.BillingEvent>read(
                makeCloudSqlQuery(options.getYearMonth()), false, row -> parseRow(row).orElse(null))
            .withCoder(BillingEventCoder.ofNullable());
    return pipeline
        .apply("Read BillingEvents from Cloud SQL", read)
        .apply("Remove null elements", Filter.by(Objects::nonNull))
        .apply("Remove duplicates", Distinct.create());
  }

  private static Optional<google.registry.beam.billing.BillingEvent> parseRow(Object[] row) {
    BillingEvent billingEvent = (BillingEvent) row[0];
    Registrar registrar = (Registrar) row[1];
    CurrencyUnit currency = billingEvent.getCost().getCurrencyUnit();
    if (!registrar.getBillingAccountMap().containsKey(currency)) {
      logger.atSevere().log(
          "Registrar %s does not have a product account key for the currency unit: %s",
          registrar.getRegistrarId(), currency);
      return Optional.empty();
    }

    return Optional.of(
        google.registry.beam.billing.BillingEvent.create(
            billingEvent.getId(),
            billingEvent.getBillingTime(),
            billingEvent.getEventTime(),
            registrar.getRegistrarId(),
            registrar.getBillingAccountMap().get(currency),
            registrar.getPoNumber().orElse(""),
            DomainNameUtils.getTldFromDomainName(billingEvent.getTargetId()),
            billingEvent.getReason().toString(),
            billingEvent.getTargetId(),
            billingEvent.getDomainRepoId(),
            Optional.ofNullable(billingEvent.getPeriodYears()).orElse(0),
            billingEvent.getCost().getCurrencyUnit().toString(),
            billingEvent.getCost().getAmount().doubleValue(),
            String.join(
                " ",
                billingEvent.getFlags().stream().map(Flag::toString).collect(toImmutableSet()))));
  }

  /** Transform that converts a {@code BillingEvent} into an invoice CSV row. */
  private static class GenerateInvoiceRows
      extends PTransform<
          PCollection<google.registry.beam.billing.BillingEvent>, PCollection<String>> {

    @Serial private static final long serialVersionUID = -8090619008258393728L;

    @Override
    public PCollection<String> expand(
        PCollection<google.registry.beam.billing.BillingEvent> input) {
      return input
          .apply(
              "Map to invoicing key",
              MapElements.into(TypeDescriptor.of(InvoiceGroupingKey.class))
                  .via(google.registry.beam.billing.BillingEvent::getInvoiceGroupingKey))
          .setCoder(InvoiceGroupingKeyCoder.of())
          .apply(
              "Filter out free events", Filter.by((InvoiceGroupingKey key) -> key.unitPrice() != 0))
          .apply("Count occurrences", Count.perElement())
          .apply(
              "Format as CSVs",
              MapElements.into(strings())
                  .via((KV<InvoiceGroupingKey, Long> kv) -> kv.getKey().toCsv(kv.getValue())));
    }
  }

  /**
   * Saves the billing events to a single overall invoice CSV file. TextIO always produces the file
   * of type text/plain, which we then update to desired text/csv before sending an email to billing
   * team {@link google.registry.reporting.billing.BillingEmailUtils#emailOverallInvoice()
   * emailOverallInvoice}
   */
  static void saveInvoiceCsv(
      PCollection<google.registry.beam.billing.BillingEvent> billingEvents,
      InvoicingPipelineOptions options) {
    billingEvents
        .apply("Generate overall invoice rows", new GenerateInvoiceRows())
        .apply(
            "Write overall invoice to CSV",
            TextIO.write()
                .to(
                    String.format(
                        "%s/%s/%s/%s-%s",
                        options.getBillingBucketUrl(),
                        BillingModule.INVOICES_DIRECTORY,
                        options.getYearMonth(),
                        options.getInvoiceFilePrefix(),
                        options.getYearMonth()))
                .withHeader(InvoiceGroupingKey.invoiceHeader())
                .withoutSharding()
                .withSuffix(".csv"));
  }

  /** Saves the billing events to detailed report CSV files keyed by registrar-tld pairs. */
  static void saveDetailedCsv(
      PCollection<google.registry.beam.billing.BillingEvent> billingEvents,
      InvoicingPipelineOptions options) {
    String yearMonth = options.getYearMonth();
    billingEvents.apply(
        "Write detailed report for each registrar-tld pair",
        FileIO.<String, google.registry.beam.billing.BillingEvent>writeDynamic()
            .to(
                String.format(
                    "%s/%s/%s",
                    options.getBillingBucketUrl(), BillingModule.INVOICES_DIRECTORY, yearMonth))
            .by(google.registry.beam.billing.BillingEvent::getDetailedReportGroupingKey)
            .withNumShards(1)
            .withDestinationCoder(StringUtf8Coder.of())
            .withNaming(
                key ->
                    (window, pane, numShards, shardIndex, compression) ->
                        String.format(
                            "%s_%s_%s.csv", BillingModule.DETAIL_REPORT_PREFIX, yearMonth, key))
            .via(
                Contextful.fn(google.registry.beam.billing.BillingEvent::toCsv),
                TextIO.sink().withHeader(google.registry.beam.billing.BillingEvent.getHeader())));
  }

  /** Create the Cloud SQL query for a given yearMonth at runtime. */
  static String makeCloudSqlQuery(String yearMonth) {
    YearMonth endMonth = YearMonth.parse(yearMonth).plusMonths(1);
    String queryWithComments =
        SqlTemplate.create(
                ResourceUtils.readResourceUtf8(
                    InvoicingPipeline.class, "sql/cloud_sql_billing_events.sql"))
            .put("FIRST_TIMESTAMP_OF_MONTH", yearMonth + "-01T00:00:00Z")
            .put(
                "LAST_TIMESTAMP_OF_MONTH",
                String.format("%d-%d-01T00:00:00Z", endMonth.getYear(), endMonth.getMonthValue()))
            .build();
    // Remove the comments from the query string
    return SQL_COMMENT_REGEX.matcher(queryWithComments).replaceAll("");
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(InvoicingPipelineOptions.class);
    InvoicingPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(InvoicingPipelineOptions.class);
    new InvoicingPipeline(options).run();
  }
}
