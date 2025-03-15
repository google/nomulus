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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.model.domain.Domain;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import google.registry.util.Retrier;
import google.registry.util.UtilsModule;
import jakarta.inject.Singleton;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
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
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha
 * --pipeline=spec11}.
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

  Spec11Pipeline(Spec11PipelineOptions options, EvaluateSafeBrowsingFn safeBrowsingFn) {
    this.options = options;
    this.safeBrowsingFn = safeBrowsingFn;
  }

  PipelineResult run() {
    Pipeline pipeline = Pipeline.create(options);
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    PCollection<DomainNameInfo> domains = readFromCloudSql(pipeline);

    PCollection<KV<DomainNameInfo, ThreatMatch>> threatMatches =
        domains.apply("Run through SafeBrowsing API", ParDo.of(safeBrowsingFn));

    saveToSql(threatMatches, options);
    saveToGcs(threatMatches, options);
  }

  static PCollection<DomainNameInfo> readFromCloudSql(Pipeline pipeline) {
    Read<Object[], KV<String, String>> read =
        RegistryJpaIO.read(
                "select d.repoId, r.emailAddress from Domain d join Registrar r on"
                    + " d.currentSponsorRegistrarId = r.registrarId where r.type = 'REAL' and"
                    + " d.deletionTime > CAST(now() AS timestamp)",
                false,
                Spec11Pipeline::parseRow)
            .withCoder(KvCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of()));

    return pipeline
        .apply("Read active domains from Cloud SQL", read)
        .apply(
            "Build DomainNameInfo",
            ParDo.of(
                new DoFn<KV<String, String>, DomainNameInfo>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<String, String> input, OutputReceiver<DomainNameInfo> output) {
                    Domain domain =
                        tm().transact(
                                () -> tm().loadByKey(VKey.create(Domain.class, input.getKey())));
                    String emailAddress = input.getValue();
                    if (emailAddress == null) {
                      emailAddress = "";
                    }
                    DomainNameInfo domainNameInfo =
                        DomainNameInfo.create(
                            domain.getDomainName(),
                            domain.getRepoId(),
                            domain.getCurrentSponsorRegistrarId(),
                            emailAddress);
                    output.output(domainNameInfo);
                  }
                }));
  }

  private static KV<String, String> parseRow(Object[] row) {
    return KV.of((String) row[0], (String) row[1]);
  }

  static void saveToSql(
      PCollection<KV<DomainNameInfo, ThreatMatch>> threatMatches, Spec11PipelineOptions options) {
    LocalDate date = LocalDate.parse(options.getDate(), ISODateTimeFormat.date());
    String transformId = "Spec11 Threat Matches";
    threatMatches
        .apply(
            "Construct objects",
            ParDo.of(
                new DoFn<KV<DomainNameInfo, ThreatMatch>, Spec11ThreatMatch>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<DomainNameInfo, ThreatMatch> input,
                      OutputReceiver<Spec11ThreatMatch> output) {
                    Spec11ThreatMatch spec11ThreatMatch =
                        new Spec11ThreatMatch.Builder()
                            .setThreatTypes(
                                ImmutableSet.of(ThreatType.valueOf(input.getValue().threatType())))
                            .setCheckDate(date)
                            .setDomainName(input.getKey().domainName())
                            .setDomainRepoId(input.getKey().domainRepoId())
                            .setRegistrarId(input.getKey().registrarId())
                            // TODO(b/264416932) Assign id to prevent duplicate inserts.
                            .build();
                    output.output(spec11ThreatMatch);
                  }
                }))
        .apply("Prevent Fusing", Reshuffle.viaRandomKey())
        .apply(
            "Write to Sql: " + transformId,
            RegistryJpaIO.<Spec11ThreatMatch>write()
                .withName(transformId)
                .withBatchSize(options.getSqlWriteBatchSize()));
  }

  static void saveToGcs(
      PCollection<KV<DomainNameInfo, ThreatMatch>> threatMatches, Spec11PipelineOptions options) {
    threatMatches
        .apply(
            "Map registrar ID to email/ThreatMatch pair",
            MapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(), TypeDescriptor.of(EmailAndThreatMatch.class)))
                .via(
                    (KV<DomainNameInfo, ThreatMatch> kv) ->
                        KV.of(
                            kv.getKey().registrarId(),
                            new EmailAndThreatMatch(
                                kv.getKey().registrarEmailAddress(), kv.getValue()))))
        .apply("Group by registrar client ID", GroupByKey.create())
        .apply(
            "Convert results to JSON format",
            MapElements.into(TypeDescriptors.strings())
                .via(
                    (KV<String, Iterable<EmailAndThreatMatch>> kv) -> {
                      String registrarId = kv.getKey();
                      checkArgument(
                          kv.getValue().iterator().hasNext(),
                          String.format(
                              "Registrar with ID %s had no corresponding threats", registrarId));
                      String email = kv.getValue().iterator().next().email();
                      JSONObject output = new JSONObject();
                      try {
                        output.put(REGISTRAR_CLIENT_ID_FIELD, registrarId);
                        output.put(REGISTRAR_EMAIL_FIELD, email);
                        JSONArray threatMatchArray = new JSONArray();
                        for (EmailAndThreatMatch emailAndThreatMatch : kv.getValue()) {
                          threatMatchArray.put(emailAndThreatMatch.threatMatch().toJSON());
                        }
                        output.put(THREAT_MATCHES_FIELD, threatMatchArray);
                        return output.toString();
                      } catch (JSONException e) {
                        throw new RuntimeException(
                            String.format("Encountered an error constructing the JSON for %s", kv),
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
                .withHeader("Map from registrar email / name to detected domain name threats:"));
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

  record EmailAndThreatMatch(String email, ThreatMatch threatMatch) implements Serializable {}
}
