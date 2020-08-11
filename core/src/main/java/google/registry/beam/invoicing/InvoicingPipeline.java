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

import com.google.auth.oauth2.GoogleCredentials;
import google.registry.beam.invoicing.BillingEvent.InvoiceGroupingKey;
import google.registry.beam.invoicing.BillingEvent.InvoiceGroupingKey.InvoiceGroupingKeyCoder;
import google.registry.config.CredentialModule.LocalCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.reporting.billing.BillingModule;
import google.registry.reporting.billing.GenerateInvoicesAction;
import google.registry.util.GoogleCredentialsBundle;
import java.io.Serializable;
import javax.inject.Inject;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.DefaultFilenamePolicy.Params;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;

/**
 * Definition of a Dataflow pipeline template, which generates a given month's invoices.
 *
 * <p>To stage this template on GCS, run the {@link
 * google.registry.tools.DeployInvoicingPipelineCommand} Nomulus command.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 * For an example using the API client library, see {@link GenerateInvoicesAction}.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/templates/overview">Dataflow Templates</a>
 */
public class InvoicingPipeline implements Serializable {

  private final String projectId;
  private final String beamJobRegion;
  private final String beamBucketUrl;
  private final String invoiceTemplateUrl;
  private final String beamStagingUrl;
  private final String billingBucketUrl;
  private final String invoiceFilePrefix;
  private final GoogleCredentials googleCredentials;

  @Inject
  public InvoicingPipeline(
      @Config("projectId") String projectId,
      @Config("defaultJobRegion") String beamJobRegion,
      @Config("apacheBeamBucketUrl") String beamBucketUrl,
      @Config("invoiceTemplateUrl") String invoiceTemplateUrl,
      @Config("beamStagingUrl") String beamStagingUrl,
      @Config("billingBucketUrl") String billingBucketUrl,
      @Config("invoiceFilePrefix") String invoiceFilePrefix,
      @LocalCredential GoogleCredentialsBundle googleCredentialsBundle) {
    this.projectId = projectId;
    this.beamJobRegion = beamJobRegion;
    this.beamBucketUrl = beamBucketUrl;
    this.invoiceTemplateUrl = invoiceTemplateUrl;
    this.beamStagingUrl = beamStagingUrl;
    this.billingBucketUrl = billingBucketUrl;
    this.invoiceFilePrefix = invoiceFilePrefix;
    this.googleCredentials = googleCredentialsBundle.getGoogleCredentials();
  }

  /** Custom options for running the invoicing pipeline. */
  public interface InvoicingPipelineOptions extends DataflowPipelineOptions {
    /** Returns the yearMonth we're generating invoices for, in yyyy-MM format. */
    @Description("The yearMonth we generate invoices for, in yyyy-MM format.")
    ValueProvider<String> getYearMonth();
    /**
     * Sets the yearMonth we generate invoices for.
     *
     * <p>This is implicitly set when executing the Dataflow template, by specifying the 'yearMonth
     * parameter.
     */
    void setYearMonth(ValueProvider<String> value);
  }

  /** Deploys the invoicing pipeline as a template on GCS, for a given projectID and GCS bucket. */
  public void deploy() {
    // We can't store options as a member variable due to serialization concerns.
    InvoicingPipelineOptions options = PipelineOptionsFactory.as(InvoicingPipelineOptions.class);
    options.setProject(projectId);
    options.setRegion(beamJobRegion);
    options.setRunner(DataflowRunner.class);
    // This causes p.run() to stage the pipeline as a template on GCS, as opposed to running it.
    options.setTemplateLocation(invoiceTemplateUrl);
    options.setStagingLocation(beamStagingUrl);
    // This credential is used when Dataflow deploys the template to GCS in target GCP project.
    // So, make sure the credential has write permission to GCS in that project.
    options.setGcpCredential(googleCredentials);

    Pipeline p = Pipeline.create(options);

    PCollection<BillingEvent> billingEvents =
        p.apply(
            "Read BillingEvents from Bigquery",
            BigQueryIO.read(BillingEvent::parseFromRecord)
                .fromQuery(InvoicingUtils.makeQueryProvider(options.getYearMonth(), projectId))
                .withCoder(SerializableCoder.of(BillingEvent.class))
                .usingStandardSql()
                .withoutValidation()
                .withTemplateCompatibility());
    applyTerminalTransforms(billingEvents, options.getYearMonth());
    p.run();
  }

  /**
   * Applies output transforms to the {@code BillingEvent} source collection.
   *
   * <p>This is factored out purely to facilitate testing.
   */
  void applyTerminalTransforms(
      PCollection<BillingEvent> billingEvents, ValueProvider<String> yearMonthProvider) {
    billingEvents
        .apply("Generate overall invoice rows", new GenerateInvoiceRows())
        .apply("Write overall invoice to CSV", writeInvoice(yearMonthProvider));

    billingEvents.apply(
        "Write detail reports to separate CSVs keyed by registrarId_tld pair",
        writeDetailReports(yearMonthProvider));
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
          .apply(Filter.by((InvoiceGroupingKey key) -> key.unitPrice() != 0))
          .setCoder(new InvoiceGroupingKeyCoder())
          .apply("Count occurrences", Count.perElement())
          .apply(
              "Format as CSVs",
              MapElements.into(TypeDescriptors.strings())
                  .via((KV<InvoiceGroupingKey, Long> kv) -> kv.getKey().toCsv(kv.getValue())));
    }
  }

  /** Returns an IO transform that writes the overall invoice to a single CSV file. */
  private TextIO.Write writeInvoice(ValueProvider<String> yearMonthProvider) {
    return TextIO.write()
        .to(
            NestedValueProvider.of(
                yearMonthProvider,
                yearMonth ->
                    String.format(
                        "%s/%s/%s/%s-%s",
                        billingBucketUrl,
                        BillingModule.INVOICES_DIRECTORY,
                        yearMonth,
                        invoiceFilePrefix,
                        yearMonth)))
        .withHeader(InvoiceGroupingKey.invoiceHeader())
        .withoutSharding()
        .withSuffix(".csv");
  }

  /** Returns an IO transform that writes detail reports to registrar-tld keyed CSV files. */
  private TextIO.TypedWrite<BillingEvent, Params> writeDetailReports(
      ValueProvider<String> yearMonthProvider) {
    return TextIO.<BillingEvent>writeCustomType()
        .to(
            InvoicingUtils.makeDestinationFunction(
                String.format("%s/%s", billingBucketUrl, BillingModule.INVOICES_DIRECTORY),
                yearMonthProvider),
            InvoicingUtils.makeEmptyDestinationParams(billingBucketUrl + "/errors"))
        .withFormatFunction(BillingEvent::toCsv)
        .withoutSharding()
        .withTempDirectory(
            FileBasedSink.convertToFileResourceIfPossible(beamBucketUrl + "/temporary"))
        .withHeader(BillingEvent.getHeader())
        .withSuffix(".csv");
  }
}
