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

package google.registry.beam.spec11;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.beam.BeamUtils.getQueryFromFile;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import google.registry.backup.AppEngineEnvironment;
import google.registry.beam.initsql.Transforms.SerializableSupplier;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.config.CredentialModule.LocalCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;
import google.registry.util.SqlTemplate;
import java.io.Serializable;
import javax.inject.Inject;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Definition of a Dataflow pipeline template, which generates a given month's spec11 report.
 *
 * <p>To stage this template on GCS, run the {@link
 * google.registry.tools.DeploySpec11PipelineCommand} Nomulus command.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/templates/overview">Dataflow Templates</a>
 */
public class Spec11Pipeline implements Serializable {

  /**
   * Returns the subdirectory spec11 reports reside in for a given local date in yyyy-MM-dd format.
   *
   * @see google.registry.beam.spec11.Spec11Pipeline
   * @see google.registry.reporting.spec11.Spec11EmailUtils
   */
  public static String getSpec11ReportFilePath(LocalDate localDate) {
    YearMonth yearMonth = new YearMonth(localDate);
    return String.format("icann/spec11/%s/SPEC11_MONTHLY_REPORT_%s", yearMonth, localDate);
  }

  /** The JSON object field into which we put the registrar's e-mail address for Spec11 reports. */
  public static final String REGISTRAR_EMAIL_FIELD = "registrarEmailAddress";
  /** The JSON object field into which we put the registrar's name for Spec11 reports. */
  public static final String REGISTRAR_CLIENT_ID_FIELD = "registrarClientId";
  /** The JSON object field into which we put the threat match array for Spec11 reports. */
  public static final String THREAT_MATCHES_FIELD = "threatMatches";

  private final String projectId;
  private final String beamStagingUrl;
  private final String spec11TemplateUrl;
  private final String reportingBucketUrl;
  private final GoogleCredentials googleCredentials;
  private final Retrier retrier;
  private final SerializableSupplier<JpaTransactionManager> jpaSupplierFactory;

  @Inject
  public Spec11Pipeline(
      @Config("projectId") String projectId,
      @Config("beamStagingUrl") String beamStagingUrl,
      @Config("spec11TemplateUrl") String spec11TemplateUrl,
      @Config("reportingBucketUrl") String reportingBucketUrl,
      SerializableSupplier<JpaTransactionManager> jpaSupplierFactory,
      @LocalCredential GoogleCredentialsBundle googleCredentialsBundle,
      Retrier retrier) {
    this.projectId = projectId;
    this.beamStagingUrl = beamStagingUrl;
    this.spec11TemplateUrl = spec11TemplateUrl;
    this.reportingBucketUrl = reportingBucketUrl;
    this.jpaSupplierFactory = jpaSupplierFactory;
    this.googleCredentials = googleCredentialsBundle.getGoogleCredentials();
    this.retrier = retrier;
  }

  /** Custom options for running the spec11 pipeline. */
  public interface Spec11PipelineOptions extends DataflowPipelineOptions {
    /** Returns the local date we're generating the report for, in yyyy-MM-dd format. */
    @Description("The local date we generate the report for, in yyyy-MM-dd format.")
    ValueProvider<String> getDate();

    /**
     * Sets the local date we generate invoices for.
     *
     * <p>This is implicitly set when executing the Dataflow template, by specifying the "date"
     * parameter.
     */
    void setDate(ValueProvider<String> value);

    /** Returns the SafeBrowsing API key we use to evaluate subdomain health. */
    @Description("The API key we use to access the SafeBrowsing API.")
    ValueProvider<String> getSafeBrowsingApiKey();

    /**
     * Sets the SafeBrowsing API key we use.
     *
     * <p>This is implicitly set when executing the Dataflow template, by specifying the
     * "safeBrowsingApiKey" parameter.
     */
    void setSafeBrowsingApiKey(ValueProvider<String> value);
  }

  /** Deploys the spec11 pipeline as a template on GCS. */
  public void deploy() {
    // We can't store options as a member variable due to serialization concerns.
    Spec11PipelineOptions options = PipelineOptionsFactory.as(Spec11PipelineOptions.class);
    options.setProject(projectId);
    options.setRunner(DataflowRunner.class);
    // This causes p.run() to stage the pipeline as a template on GCS, as opposed to running it.
    options.setTemplateLocation(spec11TemplateUrl);
    options.setStagingLocation(beamStagingUrl);
    // This credential is used when Dataflow deploys the template to GCS in target GCP project.
    // So, make sure the credential has write permission to GCS in that project.
    options.setGcpCredential(googleCredentials);

    Pipeline p = Pipeline.create(options);
    PCollection<Subdomain> domains =
        p.apply(
            "Read active domains from BigQuery",
            BigQueryIO.read(Subdomain::parseFromRecord)
                .fromQuery(
                    SqlTemplate.create(getQueryFromFile(Spec11Pipeline.class, "subdomains.sql"))
                        .put("PROJECT_ID", projectId)
                        .put("DATASTORE_EXPORT_DATASET", "latest_datastore_export")
                        .put("REGISTRAR_TABLE", "Registrar")
                        .put("DOMAIN_BASE_TABLE", "DomainBase")
                        .build())
                .withCoder(SerializableCoder.of(Subdomain.class))
                .usingStandardSql()
                .withoutValidation()
                .withTemplateCompatibility());

    evaluateUrlHealth(
        domains,
        new EvaluateSafeBrowsingFn(options.getSafeBrowsingApiKey(), retrier),
        options.getDate());
    p.run();
  }

  /**
   * Evaluate each {@link Subdomain} URL via the SafeBrowsing API.
   *
   * <p>This is factored out to facilitate testing.
   */
  void evaluateUrlHealth(
      PCollection<Subdomain> domains,
      EvaluateSafeBrowsingFn evaluateSafeBrowsingFn,
      ValueProvider<String> dateProvider) {

    PCollection<KV<Subdomain, ThreatMatch>> subdomainsSql =
        domains.apply("Run through SafeBrowsing API", ParDo.of(evaluateSafeBrowsingFn));
    /* Store ThreatMatch objects in SQL. */
    subdomainsSql.apply(
        ParDo.of(
            new DoFn<KV<Subdomain, ThreatMatch>, Void>() {
              @ProcessElement
              public void processElement(ProcessContext context) {
                // create the Spec11ThreatMatch from Subdomain and ThreatMatch
                try (AppEngineEnvironment env = new AppEngineEnvironment()) {
                  Spec11ThreatMatch threatMatch =
                      new Spec11ThreatMatch.Builder()
                          .setThreatTypes(
                              ImmutableSet.of(
                                  ThreatType.valueOf(context.element().getValue().threatType())))
                          .setCheckDate(
                              LocalDate.parse(dateProvider.get(), ISODateTimeFormat.date()))
                          .setDomainName(context.element().getKey().domainName())
                          .setDomainRepoId(context.element().getKey().domainRepoId())
                          .setRegistrarId(context.element().getKey().registrarId())
                          .build();
                  jpaSupplierFactory
                      .get()
                      .transact(() -> jpaSupplierFactory.get().saveNew(threatMatch));
                }
              }
            }));

    /* Store ThreatMatch objects in JSON. */
    PCollection<KV<Subdomain, ThreatMatch>> subdomainsJson =
        domains.apply("Run through SafeBrowsingAPI", ParDo.of(evaluateSafeBrowsingFn));
    subdomainsJson
        .apply(
            "Map registrar ID to email/ThreatMatch pair",
            MapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(), TypeDescriptor.of(EmailAndThreatMatch.class)))
                .via(
                    (KV<Subdomain, ThreatMatch> kv) ->
                        KV.of(
                            kv.getKey().registrarId(),
                            EmailAndThreatMatch.create(
                                kv.getKey().registrarEmailAddress(), kv.getValue()))))
        .apply("Group by registrar client ID", GroupByKey.create())
        .apply(
            "Convert results to JSON format",
            MapElements.into(TypeDescriptors.strings())
                .via(
                    (KV<String, Iterable<EmailAndThreatMatch>> kv) -> {
                      String clientId = kv.getKey();
                      checkArgument(
                          kv.getValue().iterator().hasNext(),
                          String.format(
                              "Registrar with ID %s had no corresponding threats", clientId));
                      String email = kv.getValue().iterator().next().email();
                      JSONObject output = new JSONObject();
                      try {
                        output.put(REGISTRAR_CLIENT_ID_FIELD, clientId);
                        output.put(REGISTRAR_EMAIL_FIELD, email);
                        JSONArray threatMatchArray = new JSONArray();
                        for (EmailAndThreatMatch emailAndThreatMatch : kv.getValue()) {
                          threatMatchArray.put(emailAndThreatMatch.threatMatch().toJSON());
                        }
                        output.put(THREAT_MATCHES_FIELD, threatMatchArray);
                        return output.toString();
                      } catch (JSONException e) {
                        throw new RuntimeException(
                            String.format(
                                "Encountered an error constructing the JSON for %s", kv.toString()),
                            e);
                      }
                    }))
        .apply(
            "Output to text file",
            TextIO.write()
                .to(
                    NestedValueProvider.of(
                        dateProvider,
                        date ->
                            String.format(
                                "%s/%s",
                                reportingBucketUrl,
                                getSpec11ReportFilePath(LocalDate.parse(date)))))
                .withoutSharding()
                .withHeader("Map from registrar email / name to detected subdomain threats:"));
  }

  @AutoValue
  abstract static class EmailAndThreatMatch implements Serializable {

    abstract String email();

    abstract ThreatMatch threatMatch();

    static EmailAndThreatMatch create(String email, ThreatMatch threatMatch) {
      return new AutoValue_Spec11Pipeline_EmailAndThreatMatch(email, threatMatch);
    }
  }
}
