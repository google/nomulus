// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.beam.spec11.SafeBrowsingTransforms.THREAT_TYPES;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.beam.spec11.SafeBrowsingTransforms.FetchThreatListPrefixesFn;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link SafeBrowsingTransforms}. */
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class SafeBrowsingTransformsTest {

  private static final ImmutableMap<String, String> THREAT_MAP =
      ImmutableMap.of(
          "111.com",
          "MALWARE",
          "party-night.net",
          "SOCIAL_ENGINEERING",
          "bitcoin.bank",
          "MALWARE",
          "no-email.com",
          "SOCIAL_ENGINEERING",
          "anti-anti-anti-virus.dev",
          "UNWANTED_SOFTWARE");

  private static final String REPO_ID = "repoId";
  private static final String REGISTRAR_ID = "registrarID";
  private static final String REGISTRAR_EMAIL = "email@registrar.net";

  private static ImmutableMap<DomainNameInfo, ThreatMatch> THREAT_MATCH_MAP;

  private static boolean threatListEmpty = false;

  private final CloseableHttpClient mockHttpClient =
      mock(CloseableHttpClient.class, withSettings().serializable());

  private final FakeClock clock = new FakeClock();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private PCollectionView<int[]> createPrefixView() {
    FetchThreatListPrefixesFn fetchFn =
        new FetchThreatListPrefixesFn(
            new Retrier(new FakeSleeper(clock), 1), Suppliers.ofInstance(mockHttpClient));
    return pipeline
        .apply("Retrieve API key", Create.of("API_KEY"))
        .apply("Fetch threat prefixes", ParDo.of(fetchFn))
        .setCoder(SerializableCoder.of(int[].class))
        .apply("Create prefix side input", View.asSingleton());
  }

  private EvaluateSafeBrowsingFn createSafeBrowsingFn(PCollectionView<int[]> prefixView) {
    return new EvaluateSafeBrowsingFn(
        "API_KEY",
        new Retrier(new FakeSleeper(clock), 1),
        clock,
        Suppliers.ofInstance(mockHttpClient),
        prefixView);
  }

  private static DomainNameInfo createDomainNameInfo(String url) {
    return DomainNameInfo.create(url, REPO_ID, REGISTRAR_ID, REGISTRAR_EMAIL);
  }

  private KV<DomainNameInfo, ThreatMatch> getKv(String url) {
    DomainNameInfo domainNameInfo = createDomainNameInfo(url);
    return KV.of(domainNameInfo, THREAT_MATCH_MAP.get(domainNameInfo));
  }

  @BeforeAll
  static void beforeAll() {
    ImmutableMap.Builder<DomainNameInfo, ThreatMatch> builder = new ImmutableMap.Builder<>();
    THREAT_MAP
        .entrySet()
        .forEach(
            kv ->
                builder.put(
                    createDomainNameInfo(kv.getKey()),
                    ThreatMatch.create(kv.getValue(), kv.getKey())));
    THREAT_MATCH_MAP = builder.build();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    threatListEmpty = false;
    when(mockHttpClient.execute(any(HttpPost.class))).thenAnswer(new HttpResponder());
  }

  @Test
  void testSuccess_someBadDomains() throws Exception {
    PCollectionView<int[]> prefixView = createPrefixView();
    ImmutableList<DomainNameInfo> domainNameInfos =
        ImmutableList.of(
            createDomainNameInfo("111.com"),
            createDomainNameInfo("hooli.com"),
            createDomainNameInfo("party-night.net"),
            createDomainNameInfo("anti-anti-anti-virus.dev"),
            createDomainNameInfo("no-email.com"));
    PCollection<KV<DomainNameInfo, ThreatMatch>> threats =
        pipeline
            .apply(Create.of(domainNameInfos).withCoder(SerializableCoder.of(DomainNameInfo.class)))
            .apply(ParDo.of(createSafeBrowsingFn(prefixView)).withSideInputs(prefixView));

    PAssert.that(threats)
        .containsInAnyOrder(
            getKv("111.com"),
            getKv("party-night.net"),
            getKv("anti-anti-anti-virus.dev"),
            getKv("no-email.com"));
    pipeline.run().waitUntilFinish();
  }

  @Test
  void testSuccess_noBadDomains() throws Exception {
    PCollectionView<int[]> prefixView = createPrefixView();
    ImmutableList<DomainNameInfo> domainNameInfos =
        ImmutableList.of(
            createDomainNameInfo("hello_kitty.dev"),
            createDomainNameInfo("555.com"),
            createDomainNameInfo("goodboy.net"));
    PCollection<KV<DomainNameInfo, ThreatMatch>> threats =
        pipeline
            .apply(Create.of(domainNameInfos).withCoder(SerializableCoder.of(DomainNameInfo.class)))
            .apply(ParDo.of(createSafeBrowsingFn(prefixView)).withSideInputs(prefixView));

    PAssert.that(threats).empty();
    pipeline.run().waitUntilFinish();
  }

  @Test
  void testSuccess_hashCollisionFalsePositive() {
    byte[] hash = SafeBrowsingTransforms.computeSha256("clean-domain.com/");
    int prefixInt = SafeBrowsingTransforms.getPrefixInt(hash);
    PCollectionView<int[]> prefixView =
        pipeline
            .apply("Create colliding prefix", Create.of(new int[] {prefixInt}))
            .apply("View colliding prefix", View.asSingleton());

    ImmutableList<DomainNameInfo> domainNameInfos =
        ImmutableList.of(createDomainNameInfo("clean-domain.com"));
    PCollection<KV<DomainNameInfo, ThreatMatch>> threats =
        pipeline
            .apply(Create.of(domainNameInfos).withCoder(SerializableCoder.of(DomainNameInfo.class)))
            .apply(ParDo.of(createSafeBrowsingFn(prefixView)).withSideInputs(prefixView));

    PAssert.that(threats).empty();
    pipeline.run().waitUntilFinish();
  }

  @Test
  void testSuccess_fetchThreatListPrefixes() {
    FetchThreatListPrefixesFn fetchFn =
        new FetchThreatListPrefixesFn(
            new Retrier(new FakeSleeper(clock), 1), Suppliers.ofInstance(mockHttpClient));
    PCollection<int[]> prefixDb =
        pipeline
            .apply(Create.of("API_KEY"))
            .apply(ParDo.of(fetchFn))
            .setCoder(SerializableCoder.of(int[].class));

    PAssert.that(prefixDb)
        .satisfies(
            iterable -> {
              int[] prefixes = Iterables.getOnlyElement(iterable);
              assertThat(prefixes.length).isGreaterThan(0);
              for (String badDomain : THREAT_MAP.keySet()) {
                byte[] hash = SafeBrowsingTransforms.computeSha256(badDomain + "/");
                int prefixInt = SafeBrowsingTransforms.getPrefixInt(hash);
                assertThat(Arrays.binarySearch(prefixes, prefixInt)).isAtLeast(0);
              }
              byte[] cleanHash =
                  SafeBrowsingTransforms.computeSha256("clean-domain-never-bad.com/");
              int cleanPrefixInt = SafeBrowsingTransforms.getPrefixInt(cleanHash);
              assertThat(Arrays.binarySearch(prefixes, cleanPrefixInt)).isLessThan(0);
              return null;
            });
    pipeline.run().waitUntilFinish();
  }

  @Test
  void testFailure_emptyPrefixes() {
    threatListEmpty = true;
    FetchThreatListPrefixesFn fetchFn =
        new FetchThreatListPrefixesFn(
            new Retrier(new FakeSleeper(clock), 1), Suppliers.ofInstance(mockHttpClient));
    pipeline.apply(Create.of("API_KEY")).apply(ParDo.of(fetchFn));

    PipelineExecutionException thrown =
        assertThrows(PipelineExecutionException.class, () -> pipeline.run().waitUntilFinish());
    assertThat(thrown).hasCauseThat().hasCauseThat().isInstanceOf(IOException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Expected prefix byte array length to be a positive multiple of 4, got 0");
  }

  /**
   * A serializable {@link Answer} that returns a mock HTTP response based on the HTTP request's
   * content.
   */
  static class HttpResponder implements Answer<CloseableHttpResponse>, Serializable {
    @Override
    public CloseableHttpResponse answer(InvocationOnMock invocation) throws Throwable {
      HttpPost post = (HttpPost) invocation.getArguments()[0];
      String uri = post.getURI().toString();
      if (uri.contains("threatListUpdates:fetch")) {
        return getThreatListUpdatesMockResponse(threatListEmpty);
      }
      return getMockResponse(
          CharStreams.toString(new InputStreamReader(post.getEntity().getContent(), UTF_8)));
    }
  }

  private static CloseableHttpResponse getThreatListUpdatesMockResponse() throws JSONException {
    return getThreatListUpdatesMockResponse(false);
  }

  private static CloseableHttpResponse getThreatListUpdatesMockResponse(boolean empty)
      throws JSONException {
    JSONObject response = new JSONObject();
    JSONArray listUpdateResponses = new JSONArray();

    for (String threatType : THREAT_TYPES) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      if (!empty) {
        for (Map.Entry<String, String> entry : THREAT_MAP.entrySet()) {
          if (entry.getValue().equals(threatType)) {
            byte[] hash = SafeBrowsingTransforms.computeSha256(entry.getKey() + "/");
            bytes.write(hash, 0, 4);
          }
        }
      }
      JSONObject listResponse =
          new JSONObject()
              .put("threatType", threatType)
              .put("platformType", "ANY_PLATFORM")
              .put("threatEntryType", "URL")
              .put("responseType", "FULL_UPDATE");
      listResponse.put(
          "additions",
          new JSONArray()
              .put(
                  new JSONObject()
                      .put(
                          "rawHashes",
                          new JSONObject()
                              .put("prefixSize", 4)
                              .put(
                                  "rawHashes",
                                  Base64.getEncoder().encodeToString(bytes.toByteArray())))));
      listUpdateResponses.put(listResponse);
    }
    response.put("listUpdateResponses", listUpdateResponses);

    CloseableHttpResponse httpResponse =
        mock(CloseableHttpResponse.class, withSettings().serializable());
    when(httpResponse.getStatusLine())
        .thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "Done"));
    when(httpResponse.getEntity()).thenReturn(new FakeHttpEntity(response.toString()));
    return httpResponse;
  }

  /**
   * Returns a {@link CloseableHttpResponse} containing either positive (threat found) or negative
   * (no threat) API examples based on the request data.
   */
  private static CloseableHttpResponse getMockResponse(String request) throws JSONException {
    // Determine which bad URLs are in the request (if any)
    ImmutableList<String> badUrls =
        THREAT_MAP.keySet().stream()
            .filter(request::contains)
            .collect(ImmutableList.toImmutableList());

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
              .put("threatType", THREAT_MAP.get(badUrl))
              .put("threat", new JSONObject().put("url", badUrl)));
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
}
