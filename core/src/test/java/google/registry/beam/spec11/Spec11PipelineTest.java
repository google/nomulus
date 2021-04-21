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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.truth.Correspondence;
import com.google.common.truth.Correspondence.BinaryPredicate;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.model.reporting.Spec11ThreatMatchDao;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.util.ResourceUtils;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.LocalDate;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link Spec11Pipeline}.
 *
 * <p>Unfortunately there is no emulator for BigQuery like that for Datastore or App Engine.
 * Therefore we cannot fully test the pipeline but only test the two separate sink IO functions,
 * assuming that date is sourcede correctly the {@code BigQueryIO}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class Spec11PipelineTest {
  private static final String DATE = "2020-01-27";
  private static final String SAFE_BROWSING_API_KEY = "api-key";
  private static final String REPORTING_BUCKET_URL = "reporting_bucket";

  private static final ImmutableList<Subdomain> SUBDOMAINS =
      ImmutableList.of(
          Subdomain.create("111.com", "123456789-COM", "hello-registrar", "email@hello.net"),
          Subdomain.create("party-night.net", "2244AABBC-NET", "kitty-registrar", "contact@kit.ty"),
          Subdomain.create("bitcoin.bank", "1C3D5E7F9-BANK", "hello-registrar", "email@hello.net"),
          Subdomain.create("no-email.com", "2A4BA9BBC-COM", "kitty-registrar", "contact@kit.ty"),
          Subdomain.create(
              "anti-anti-anti-virus.dev", "555666888-DEV", "cool-registrar", "cool@aid.net"));

  private static final ImmutableList<ThreatMatch> THREAT_MATCHES =
      ImmutableList.of(
          ThreatMatch.create("MALWARE", "111.com"),
          ThreatMatch.create("SOCIAL_ENGINEERING", "party-night.net"),
          ThreatMatch.create("POTENTIALLY_HARMFUL_APPLICATION", "bitcoin.bank"),
          ThreatMatch.create("THREAT_TYPE_UNSPECIFIED", "no-eamil.com"),
          ThreatMatch.create("UNWANTED_SOFTWARE", "anti-anti-anti-virus.dev"));

  // This extension is only needed because Spec11ThreatMatch uses Ofy to generate the ID. Can be
  // removed after the SQL migration.
  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @TempDir Path tmpDir;

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(new FakeClock()).buildIntegrationTestRule();

  private final Spec11PipelineOptions options =
      PipelineOptionsFactory.create().as(Spec11PipelineOptions.class);

  private File reportingBucketUrl;
  private PCollection<KV<Subdomain, ThreatMatch>> threatMatches;

  @BeforeEach
  void beforeEach() throws Exception {
    reportingBucketUrl = Files.createDirectory(tmpDir.resolve(REPORTING_BUCKET_URL)).toFile();
    options.setDate(DATE);
    options.setSafeBrowsingApiKey(SAFE_BROWSING_API_KEY);
    options.setReportingBucketUrl(reportingBucketUrl.getAbsolutePath());
    threatMatches =
        pipeline.apply(
            Create.of(
                    Streams.zip(SUBDOMAINS.stream(), THREAT_MATCHES.stream(), KV::of)
                        .collect(toImmutableList()))
                .withCoder(
                    KvCoder.of(
                        SerializableCoder.of(Subdomain.class),
                        SerializableCoder.of(ThreatMatch.class))));
  }

  @Test
  void testSuccess_saveToSql() {
    ImmutableSet<Spec11ThreatMatch> sqlThreatMatches =
        ImmutableSet.of(
            new Spec11ThreatMatch.Builder()
                .setDomainName("111.com")
                .setDomainRepoId("123456789-COM")
                .setRegistrarId("hello-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("party-night.net")
                .setDomainRepoId("2244AABBC-NET")
                .setRegistrarId("kitty-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.SOCIAL_ENGINEERING))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("bitcoin.bank")
                .setDomainRepoId("1C3D5E7F9-BANK")
                .setRegistrarId("hello-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.POTENTIALLY_HARMFUL_APPLICATION))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("no-email.com")
                .setDomainRepoId("2A4BA9BBC-COM")
                .setRegistrarId("kitty-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.THREAT_TYPE_UNSPECIFIED))
                .build(),
            new Spec11ThreatMatch.Builder()
                .setDomainName("anti-anti-anti-virus.dev")
                .setDomainRepoId("555666888-DEV")
                .setRegistrarId("cool-registrar")
                .setCheckDate(new LocalDate(2020, 1, 27))
                .setThreatTypes(ImmutableSet.of(ThreatType.UNWANTED_SOFTWARE))
                .build());
    Spec11Pipeline.saveToSql(threatMatches, options);
    pipeline.run().waitUntilFinish();
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        Spec11ThreatMatchDao.loadEntriesByDate(
                            jpaTm(), new LocalDate(2020, 1, 27))))
        .comparingElementsUsing(immutableObjectCorrespondence("id"))
        .containsExactlyElementsIn(sqlThreatMatches);
  }

  @Test
  void testSuccess_saveToGcs() throws Exception {
    ImmutableList<String> expectedFileContents =
        ImmutableList.copyOf(
            ResourceUtils.readResourceUtf8(this.getClass(), "test_output.txt").split("\n"));
    Spec11Pipeline.saveToGcs(threatMatches, options);
    pipeline.run().waitUntilFinish();
    ImmutableList<String> resultFileContents = resultFileContents();
    assertThat(resultFileContents.size()).isEqualTo(expectedFileContents.size());
    assertThat(resultFileContents.get(0)).isEqualTo(expectedFileContents.get(0));
    assertThat(resultFileContents.subList(1, resultFileContents.size()))
        .comparingElementsUsing(
            Correspondence.from(
                new ThreatMatchJsonPredicate(), "has fields with unordered threatTypes equal to"))
        .containsExactlyElementsIn(expectedFileContents.subList(1, expectedFileContents.size()));
  }

  /** Returns the text contents of a file under the beamBucket/results directory. */
  private ImmutableList<String> resultFileContents() throws Exception {
    File resultFile =
        new File(
            String.format(
                "%s/icann/spec11/2020-01/SPEC11_MONTHLY_REPORT_2020-01-27",
                reportingBucketUrl.getAbsolutePath().toString()));
    return ImmutableList.copyOf(
        ResourceUtils.readResourceUtf8(resultFile.toURI().toURL()).split("\n"));
  }

  private static class ThreatMatchJsonPredicate implements BinaryPredicate<String, String> {
    private static final String THREAT_MATCHES = "threatMatches";

    @Override
    public boolean apply(@Nullable String actual, @Nullable String expected) {
      JSONObject actualJson = new JSONObject(actual);
      JSONObject expectedJson = new JSONObject(expected);
      if (!actualJson.keySet().equals(expectedJson.keySet())) {
        return false;
      }
      for (String key : actualJson.keySet()) {
        if (key.equals(THREAT_MATCHES)) {
          if (ImmutableSet.copyOf(actualJson.getJSONArray(key))
              .equals(ImmutableSet.copyOf(expectedJson.getJSONArray(key)))) {
            return false;
          }
        } else if (!actualJson.get(key).equals(expectedJson.get(key))) {
          return false;
        }
      }
      ;
      return true;
    }
  }
}
