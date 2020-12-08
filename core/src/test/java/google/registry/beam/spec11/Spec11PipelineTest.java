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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.initsql.BeamJpaExtension;
import google.registry.beam.initsql.JpaSupplierFactory;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.persistence.PersistenceTestModule;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.ResourceUtils;
import google.registry.util.Retrier;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Supplier;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link Spec11Pipeline}. */
class Spec11PipelineTest {

  // The following fields are transient because otherwise we'd attempt to serialize them as part of
  // the ParDo functions
  private final transient FakeClock fakeClock = new FakeClock();

  @RegisterExtension final transient InjectExtension injectRule = new InjectExtension();

  @SuppressWarnings("WeakerAccess")
  @TempDir
  transient Path tmpDir;

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final transient AppEngineExtension appEngineExtension =
      AppEngineExtension.builder().withDatastore().withClock(fakeClock).build();

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  @RegisterExtension
  @Order(Order.DEFAULT + 1)
  final transient BeamJpaExtension beamJpaExtension =
      new BeamJpaExtension(() -> tmpDir.resolve("credential.dat"), database.getDatabase());

  private final Retrier retrier = new Retrier(new FakeSleeper(fakeClock), 3);
  private Spec11Pipeline spec11Pipeline;

  @BeforeEach
  void beforeEach() throws IOException {
    String beamTempFolder =
        Files.createDirectory(tmpDir.resolve("beam_temp")).toAbsolutePath().toString();

    JpaSupplierFactory jpaSupplierFactory =
        new JpaSupplierFactory(
            beamJpaExtension.getCredentialFile().getAbsolutePath(),
            "test-project",
            PersistenceTestModule::testJpaTransactionManager);
    spec11Pipeline =
        new Spec11Pipeline(
            "test-project",
            "region",
            beamTempFolder + "/staging",
            beamTempFolder + "/templates/invoicing",
            tmpDir.toAbsolutePath().toString(),
            GoogleCredentialsBundle.create(GoogleCredentials.create(null)),
            retrier,
            jpaSupplierFactory);
    createTld("tld");
  }

  private static final ImmutableList<String> BAD_DOMAINS =
      ImmutableList.of("111.com", "222.com", "444.com", "no-email.com", "bad.tld");

  private ImmutableList<Subdomain> getInputDomainsJson() {
    ImmutableList.Builder<Subdomain> subdomainsBuilder = new ImmutableList.Builder<>();
    // Put in at least 2 batches worth (x > 490) to guarantee multiple executions.
    // Put in half for theRegistrar and half for someRegistrar
    for (int i = 0; i < 255; i++) {
      subdomainsBuilder.add(
          Subdomain.create(
              String.format("%s.com", i), "theDomain", "theRegistrar", "fake@theRegistrar.com"));
    }
    for (int i = 255; i < 510; i++) {
      subdomainsBuilder.add(
          Subdomain.create(
              String.format("%s.com", i), "someDomain", "someRegistrar", "fake@someRegistrar.com"));
    }
    subdomainsBuilder.add(Subdomain.create("no-email.com", "fakeDomain", "noEmailRegistrar", ""));
    return subdomainsBuilder.build();
  }

  /**
   * Tests the end-to-end Spec11 pipeline with mocked out API calls.
   *
   * <p>We suppress the (Serializable & Supplier) dual-casted lambda warnings because the supplier
   * produces an explicitly serializable mock, which is safe to cast.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testEndToEndPipeline_generatesExpectedFiles() throws Exception {
    // Establish mocks for testing
    ImmutableList<Subdomain> inputRows = getInputDomainsJson();
    CloseableHttpClient mockHttpClient =
        mock(CloseableHttpClient.class, withSettings().serializable());

    // Return a mock HttpResponse that returns a JSON response based on the request.
    when(mockHttpClient.execute(any(HttpPost.class))).thenAnswer(new HttpResponder());

    EvaluateSafeBrowsingFn evalFn =
        new EvaluateSafeBrowsingFn(
            StaticValueProvider.of("apikey"),
            new Retrier(new FakeSleeper(new FakeClock()), 3),
            (Serializable & Supplier) () -> mockHttpClient);

    // Apply input and evaluation transforms
    PCollection<Subdomain> input = testPipeline.apply(Create.of(inputRows));
    spec11Pipeline.evaluateUrlHealth(input, evalFn, StaticValueProvider.of("2018-06-01"));
    testPipeline.run();

    // Verify header and 4 threat matches for 3 registrars are found
    ImmutableList<String> generatedReport = resultFileContents();
    assertThat(generatedReport).hasSize(4);
    assertThat(generatedReport.get(0))
        .isEqualTo("Map from registrar email / name to detected subdomain threats:");

    // The output file can put the registrar emails and bad URLs in any order.
    // We cannot rely on the JSON toString to sort because the keys are not always in the same
    // order, so we must rely on length even though that's not ideal.
    ImmutableList<String> sortedLines =
        ImmutableList.sortedCopyOf(
            Comparator.comparingInt(String::length), generatedReport.subList(1, 4));

    JSONObject noEmailRegistrarJSON = new JSONObject(sortedLines.get(0));
    assertThat(noEmailRegistrarJSON.get("registrarEmailAddress")).isEqualTo("");
    assertThat(noEmailRegistrarJSON.get("registrarClientId")).isEqualTo("noEmailRegistrar");
    assertThat(noEmailRegistrarJSON.has("threatMatches")).isTrue();
    JSONArray noEmailThreatMatch = noEmailRegistrarJSON.getJSONArray("threatMatches");
    assertThat(noEmailThreatMatch.length()).isEqualTo(1);
    assertThat(noEmailThreatMatch.getJSONObject(0).get("fullyQualifiedDomainName"))
        .isEqualTo("no-email.com");
    assertThat(noEmailThreatMatch.getJSONObject(0).get("threatType")).isEqualTo("MALWARE");

    JSONObject someRegistrarJSON = new JSONObject(sortedLines.get(1));
    assertThat(someRegistrarJSON.get("registrarEmailAddress")).isEqualTo("fake@someRegistrar.com");
    assertThat(someRegistrarJSON.get("registrarClientId")).isEqualTo("someRegistrar");
    assertThat(someRegistrarJSON.has("threatMatches")).isTrue();
    JSONArray someThreatMatch = someRegistrarJSON.getJSONArray("threatMatches");
    assertThat(someThreatMatch.length()).isEqualTo(1);
    assertThat(someThreatMatch.getJSONObject(0).get("fullyQualifiedDomainName"))
        .isEqualTo("444.com");
    assertThat(someThreatMatch.getJSONObject(0).get("threatType")).isEqualTo("MALWARE");

    // theRegistrar has two ThreatMatches, we have to parse it explicitly
    JSONObject theRegistrarJSON = new JSONObject(sortedLines.get(2));
    assertThat(theRegistrarJSON.get("registrarEmailAddress")).isEqualTo("fake@theRegistrar.com");
    assertThat(theRegistrarJSON.get("registrarClientId")).isEqualTo("theRegistrar");
    assertThat(theRegistrarJSON.has("threatMatches")).isTrue();
    JSONArray theThreatMatches = theRegistrarJSON.getJSONArray("threatMatches");
    assertThat(theThreatMatches.length()).isEqualTo(2);
    ImmutableList<String> threatMatchStrings =
        ImmutableList.of(
            theThreatMatches.getJSONObject(0).toString(),
            theThreatMatches.getJSONObject(1).toString());
    assertThat(threatMatchStrings)
        .containsExactly(
            new JSONObject()
                .put("fullyQualifiedDomainName", "111.com")
                .put("threatType", "MALWARE")
                .toString(),
            new JSONObject()
                .put("fullyQualifiedDomainName", "222.com")
                .put("threatType", "MALWARE")
                .toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSpec11ThreatMatchToSql() throws Exception {
    // Only the bad domain should be persisted in SQL
    DomainBase badDomain = newDomainBase("bad.tld");
    DomainBase goodDomain = newDomainBase("good.tld");
    Subdomain badSubdomain =
        Subdomain.create(
            badDomain.getDomainName(),
            badDomain.getRepoId(),
            badDomain.getCurrentSponsorClientId(),
            "fake@example.tld");
    Subdomain goodSubdomain =
        Subdomain.create(
            goodDomain.getDomainName(),
            goodDomain.getRepoId(),
            goodDomain.getCurrentSponsorClientId(),
            "foo@example.tld");

    // Establish a mock HttpResponse that returns a JSON response based on the request.
    CloseableHttpClient mockHttpClient =
        mock(CloseableHttpClient.class, withSettings().serializable());
    when(mockHttpClient.execute(any(HttpPost.class))).thenAnswer(new HttpResponder());

    EvaluateSafeBrowsingFn evalFn =
        new EvaluateSafeBrowsingFn(
            StaticValueProvider.of("apikey"),
            new Retrier(new FakeSleeper(new FakeClock()), 3),
            (Serializable & Supplier) () -> mockHttpClient);

    // Apply input and evaluation transforms
    PCollection<Subdomain> input = testPipeline.apply(Create.of(badSubdomain, goodSubdomain));
    spec11Pipeline.evaluateUrlHealth(input, evalFn, StaticValueProvider.of("2020-06-10"));
    testPipeline.run();

    // Verify that the expected threat created from the bad Subdomain and the persisted
    // Spec11TThreatMatch are equal.
    Spec11ThreatMatch expected =
        new Spec11ThreatMatch()
            .asBuilder()
            .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
            .setCheckDate(LocalDate.parse("2020-06-10", ISODateTimeFormat.date()))
            .setDomainName(badDomain.getDomainName())
            .setDomainRepoId(badDomain.getRepoId())
            .setRegistrarId(badDomain.getCurrentSponsorClientId())
            .build();
    assertAboutImmutableObjects()
        .that(
            jpaTm()
                .transact(
                    () -> Iterables.getOnlyElement(jpaTm().loadAllOf(Spec11ThreatMatch.class))))
        .isEqualExceptFields(expected, "id");
  }

  /**
   * A serializable {@link Answer} that returns a mock HTTP response based on the HTTP request's
   * content.
   */
  private static class HttpResponder implements Answer<CloseableHttpResponse>, Serializable {
    @Override
    public CloseableHttpResponse answer(InvocationOnMock invocation) throws Throwable {
      return getMockResponse(
          CharStreams.toString(
              new InputStreamReader(
                  ((HttpPost) invocation.getArguments()[0]).getEntity().getContent(), UTF_8)));
    }
  }

  /**
   * Returns a {@link CloseableHttpResponse} containing either positive (threat found) or negative
   * (no threat) API examples based on the request data.
   */
  private static CloseableHttpResponse getMockResponse(String request) throws JSONException {
    // Determine which bad URLs are in the request (if any)
    ImmutableList<String> badUrls =
        BAD_DOMAINS.stream().filter(request::contains).collect(ImmutableList.toImmutableList());

    CloseableHttpResponse httpResponse =
        mock(CloseableHttpResponse.class, withSettings().serializable());
    when(httpResponse.getStatusLine())
        .thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "Done"));
    when(httpResponse.getEntity()).thenReturn(new FakeHttpEntity(getAPIResponse(badUrls)));
    return httpResponse;
  }

  /**
   * Returns the expected API response for a list of bad URLs.
   *
   * <p>If there are no badUrls in the list, this returns the empty JSON string "{}".
   */
  private static String getAPIResponse(ImmutableList<String> badUrls) throws JSONException {
    JSONObject response = new JSONObject();
    if (badUrls.isEmpty()) {
      return response.toString();
    }
    // Create a threatMatch for each badUrl
    JSONArray matches = new JSONArray();
    for (String badUrl : badUrls) {
      matches.put(
          new JSONObject()
              .put("threatType", "MALWARE")
              .put("platformType", "WINDOWS")
              .put("threatEntryType", "URL")
              .put("threat", new JSONObject().put("url", badUrl))
              .put("cacheDuration", "300.000s"));
    }
    response.put("matches", matches);
    return response.toString();
  }

  /** A serializable HttpEntity fake that returns {@link String} content. */
  private static class FakeHttpEntity extends BasicHttpEntity implements Serializable {

    private static final long serialVersionUID = 105738294571L;

    private String content;

    private void writeObject(ObjectOutputStream oos) throws IOException {
      oos.defaultWriteObject();
    }

    /**
     * Sets the {@link FakeHttpEntity} content upon deserialization.
     *
     * <p>This allows us to use {@link #getContent()} as-is, fully emulating the behavior of {@link
     * BasicHttpEntity} regardless of serialization.
     */
    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
      ois.defaultReadObject();
      super.setContent(new ByteArrayInputStream(this.content.getBytes(UTF_8)));
    }

    FakeHttpEntity(String content) {
      this.content = content;
      super.setContent(new ByteArrayInputStream(this.content.getBytes(UTF_8)));
    }
  }

  /** Returns the text contents of a file under the beamBucket/results directory. */
  private ImmutableList<String> resultFileContents() throws Exception {
    File resultFile =
        new File(
            String.format(
                "%s/icann/spec11/2018-06/SPEC11_MONTHLY_REPORT_2018-06-01",
                tmpDir.toAbsolutePath().toString()));
    return ImmutableList.copyOf(
        ResourceUtils.readResourceUtf8(resultFile.toURI().toURL()).split("\n"));
  }
}
