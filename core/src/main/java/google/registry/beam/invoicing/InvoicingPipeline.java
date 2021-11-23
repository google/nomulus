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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.beam.BeamUtils.getQueryFromFile;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.beam.invoicing.BillingEvent.InvoiceGroupingKey;
import google.registry.beam.invoicing.BillingEvent.InvoiceGroupingKey.InvoiceGroupingKeyCoder;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.reporting.billing.BillingModule;
import google.registry.util.DateTimeUtils;
import google.registry.util.DomainNameUtils;
import google.registry.util.SqlTemplate;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Contextful;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

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

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  private static final Pattern SQL_COMMENT_REGEX =
      Pattern.compile("^\\s*--.*\\n", Pattern.MULTILINE);

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
    PCollection<BillingEvent> billingEvents =
        options.getDatabase().equals("DATASTORE")
            ? readFromBigQuery(options, pipeline)
            : readFromCloudSql(options, pipeline);

    saveInvoiceCsv(billingEvents, options);

    saveDetailedCsv(billingEvents, options);
  }

  static PCollection<BillingEvent> readFromBigQuery(
      InvoicingPipelineOptions options, Pipeline pipeline) {
    return pipeline.apply(
        "Read BillingEvents from Bigquery",
        BigQueryIO.read(BillingEvent::parseFromRecord)
            .fromQuery(makeQuery(options.getYearMonth(), options.getProject()))
            .withCoder(SerializableCoder.of(BillingEvent.class))
            .usingStandardSql()
            .withoutValidation()
            .withTemplateCompatibility());
  }

  static PCollection<BillingEvent> readFromCloudSql(
      InvoicingPipelineOptions options, Pipeline pipeline) {
    Read<Object[], BillingEvent> read =
        RegistryJpaIO.read(
            makeCloudSqlQuery(options.getYearMonth()), false, InvoicingPipeline::parseRow);

    return pipeline.apply("Read BillingEvents from Cloud SQL", read);
  }

  private static BillingEvent parseRow(Object[] row) {
    google.registry.model.billing.BillingEvent.OneTime oneTime =
        (google.registry.model.billing.BillingEvent.OneTime) row[0];
    Registrar registrar = (Registrar) row[1];
    return BillingEvent.create(
        oneTime.getId(),
        DateTimeUtils.toZonedDateTime(oneTime.getBillingTime(), ZoneId.of("UTC")),
        DateTimeUtils.toZonedDateTime(oneTime.getEventTime(), ZoneId.of("UTC")),
        registrar.getRegistrarId(),
        registrar.getBillingIdentifier().toString(),
        registrar.getPoNumber().orElse(""),
        DomainNameUtils.getTldFromDomainName(oneTime.getTargetId()),
        oneTime.getReason().toString(),
        oneTime.getTargetId(),
        oneTime.getDomainRepoId(),
        Optional.ofNullable(oneTime.getPeriodYears()).orElse(0),
        oneTime.getCost().getCurrencyUnit().toString(),
        oneTime.getCost().getAmount().doubleValue(),
        String.join(
            " ", oneTime.getFlags().stream().map(Flag::toString).collect(toImmutableSet())));
  }

  /** Transform that converts a {@code BillingEvent} into an invoice CSV row. */
  private static class GenerateInvoiceRows
      extends PTransform<PCollection<BillingEvent>, PCollection<String>> {
    @Override
    public PCollection<String> expand(PCollection<BillingEvent> input) {
      return input
          .apply(
              "Map to invoicing key",
              MapElements.into(TypeDescriptor.of(InvoiceGroupingKey.class))
                  .via(BillingEvent::getInvoiceGroupingKey))
          .apply(
              "Filter out free events", Filter.by((InvoiceGroupingKey key) -> key.unitPrice() != 0))
          .setCoder(new InvoiceGroupingKeyCoder())
          .apply("Count occurrences", Count.perElement())
          .apply(
              "Format as CSVs",
              MapElements.into(strings())
                  .via((KV<InvoiceGroupingKey, Long> kv) -> kv.getKey().toCsv(kv.getValue())));
    }
  }

  /** Saves the billing events to a single overall invoice CSV file. */
  static void saveInvoiceCsv(
      PCollection<BillingEvent> billingEvents, InvoicingPipelineOptions options) {
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
      PCollection<BillingEvent> billingEvents, InvoicingPipelineOptions options) {
    String yearMonth = options.getYearMonth();
    billingEvents.apply(
        "Write detailed report for each registrar-tld pair",
        FileIO.<String, BillingEvent>writeDynamic()
            .to(
                String.format(
                    "%s/%s/%s",
                    options.getBillingBucketUrl(), BillingModule.INVOICES_DIRECTORY, yearMonth))
            .by(BillingEvent::getDetailedReportGroupingKey)
            .withNumShards(1)
            .withDestinationCoder(StringUtf8Coder.of())
            .withNaming(
                key ->
                    (window, pane, numShards, shardIndex, compression) ->
                        String.format(
                            "%s_%s_%s.csv", BillingModule.DETAIL_REPORT_PREFIX, yearMonth, key))
            .via(
                Contextful.fn(BillingEvent::toCsv),
                TextIO.sink().withHeader(BillingEvent.getHeader())));
  }

  /** Create the Bigquery query for a given project and yearMonth at runtime. */
  static String makeQuery(String yearMonth, String projectId) {
    // Get the timestamp endpoints capturing the entire month with microsecond precision
    YearMonth reportingMonth = YearMonth.parse(yearMonth);
    LocalDateTime firstMoment = reportingMonth.atDay(1).atTime(LocalTime.MIDNIGHT);
    LocalDateTime lastMoment = reportingMonth.atEndOfMonth().atTime(LocalTime.MAX);
    // Construct the month's query by filling in the billing_events.sql template
    return SqlTemplate.create(getQueryFromFile(InvoicingPipeline.class, "billing_events.sql"))
        .put("FIRST_TIMESTAMP_OF_MONTH", firstMoment.format(TIMESTAMP_FORMATTER))
        .put("LAST_TIMESTAMP_OF_MONTH", lastMoment.format(TIMESTAMP_FORMATTER))
        .put("PROJECT_ID", projectId)
        .put("DATASTORE_EXPORT_DATA_SET", "latest_datastore_export")
        .put("ONETIME_TABLE", "OneTime")
        .put("REGISTRY_TABLE", "Registry")
        .put("REGISTRAR_TABLE", "Registrar")
        .put("CANCELLATION_TABLE", "Cancellation")
        .build();
  }

  /** Create the Cloud SQL query for a given yearMonth at runtime. */
  static String makeCloudSqlQuery(String yearMonth) {
    YearMonth endMonth = YearMonth.parse(yearMonth).plusMonths(1);
    String queryWithComments =
        SqlTemplate.create(
                getQueryFromFile(InvoicingPipeline.class, "cloud_sql_billing_events.sql"))
            .put("FIRST_TIMESTAMP_OF_MONTH", yearMonth.concat("-01"))
            .put(
                "LAST_TIMESTAMP_OF_MONTH",
                String.format("%d-%d-01", endMonth.getYear(), endMonth.getMonthValue()))
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
