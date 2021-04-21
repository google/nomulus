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

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.util.Retrier;
import google.registry.util.SqlTemplate;
import google.registry.util.UtilsModule;
import java.io.Serializable;
import javax.inject.Singleton;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
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
 * Definition of a Dataflow Flex template, which generates a given month's spec11 report.
 *
 * <p>To stage this template locally, run the {@code stage_beam_pipeline.sh} shell script.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
public class Spec11Pipeline implements Serializable {

  /**
   * Returns the subdirectory spec11 reports reside in for a given local date in yyyy-MM-dd format.
   *
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

  private final Spec11PipelineOptions options;
  private final EvaluateSafeBrowsingFn safeBrowsingFn;
  private final Pipeline pipeline;

  @VisibleForTesting
  Spec11Pipeline(
      Spec11PipelineOptions options, EvaluateSafeBrowsingFn safeBrowsingFn, Pipeline pipeline) {
    this.options = options;
    this.safeBrowsingFn = safeBrowsingFn;
    this.pipeline = pipeline;
  }

  Spec11Pipeline(Spec11PipelineOptions options, EvaluateSafeBrowsingFn safeBrowsingFn) {
    this(options, safeBrowsingFn, Pipeline.create(options));
  }

  PipelineResult run() {
    setupPipeline();
    return pipeline.run();
  }

  void setupPipeline() {
    PCollection<Subdomain> domains =
        pipeline.apply(
            "Read active domains from BigQuery",
            BigQueryIO.read(Subdomain::parseFromRecord)
                .fromQuery(
                    SqlTemplate.create(getQueryFromFile(Spec11Pipeline.class, "subdomains.sql"))
                        .put("PROJECT_ID", options.getProject())
                        .put("DATASTORE_EXPORT_DATASET", "latest_datastore_export")
                        .put("REGISTRAR_TABLE", "Registrar")
                        .put("DOMAIN_BASE_TABLE", "DomainBase")
                        .build())
                .withCoder(SerializableCoder.of(Subdomain.class))
                .usingStandardSql()
                .withoutValidation()
                .withTemplateCompatibility());

    PCollection<KV<Subdomain, ThreatMatch>> threatMatches =
        domains.apply("Run through SafeBrowsing API", ParDo.of(safeBrowsingFn));

    saveToSql(threatMatches, options);
    saveToGcs(threatMatches, options);
  }

  static void saveToSql(
      PCollection<KV<Subdomain, ThreatMatch>> threatMatches, Spec11PipelineOptions options) {
    String transformId = "Spec11 Threat Matches";
    LocalDate date = LocalDate.parse(options.getDate(), ISODateTimeFormat.date());
    threatMatches.apply(
        "Write to Sql: " + transformId,
        RegistryJpaIO.<KV<Subdomain, ThreatMatch>>write()
            .withName(transformId)
            .withBatchSize(options.getSqlWriteBatchSize())
            .withShards(options.getSqlWriteShards())
            .withJpaConverter(
                (kv) -> {
                  Subdomain subdomain = kv.getKey();
                  return new Spec11ThreatMatch.Builder()
                      .setThreatTypes(
                          ImmutableSet.of(ThreatType.valueOf(kv.getValue().threatType())))
                      .setCheckDate(date)
                      .setDomainName(subdomain.domainName())
                      .setDomainRepoId(subdomain.domainRepoId())
                      .setRegistrarId(subdomain.registrarId())
                      .build();
                }));
  }

  static void saveToGcs(
      PCollection<KV<Subdomain, ThreatMatch>> threatMatches, Spec11PipelineOptions options) {
    threatMatches
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
                    String.format(
                        "%s/%s",
                        options.getReportingBucketUrl(),
                        getSpec11ReportFilePath(LocalDate.parse(options.getDate()))))
                .withoutSharding()
                .withHeader("Map from registrar email / name to detected subdomain threats:"));
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(Spec11PipelineOptions.class);
    DaggerSpec11Pipeline_Spec11PipelineComponent.builder()
        .spec11PipelineModule(new Spec11PipelineModule(args))
        .build()
        .spec11Pipeline()
        .run();
  }

  @Module
  static class Spec11PipelineModule {
    private final String[] args;

    Spec11PipelineModule(String[] args) {
      this.args = args;
    }

    @Provides
    Spec11PipelineOptions provideOptions() {
      return PipelineOptionsFactory.fromArgs(args).withValidation().as(Spec11PipelineOptions.class);
    }

    @Provides
    EvaluateSafeBrowsingFn provideSafeBrowsingFn(Spec11PipelineOptions options, Retrier retrier) {
      return new EvaluateSafeBrowsingFn(options.getSafeBrowsingApiKey(), retrier);
    }

    @Provides
    Spec11Pipeline providePipeline(
        Spec11PipelineOptions options, EvaluateSafeBrowsingFn safeBrowsingFn) {
      return new Spec11Pipeline(options, safeBrowsingFn);
    }
  }

  @Component(modules = {Spec11PipelineModule.class, UtilsModule.class, ConfigModule.class})
  @Singleton
  interface Spec11PipelineComponent {
    Spec11Pipeline spec11Pipeline();
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
