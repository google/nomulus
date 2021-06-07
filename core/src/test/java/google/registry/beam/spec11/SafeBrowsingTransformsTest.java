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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
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
          "POTENTIALLY_HARMFUL_APPLICATION",
          "no-email.com",
          "THREAT_TYPE_UNSPECIFIED",
          "anti-anti-anti-virus.dev",
          "UNWANTED_SOFTWARE");

  private static final String REPO_ID = "repoId";
  private static final String REGISTRAR_ID = "registrarID";
  private static final String REGISTRAR_EMAIL = "email@registrar.net";

  private static ImmutableMap<Subdomain, ThreatMatch> THREAT_MATCH_MAP;

  private final CloseableHttpClient mockHttpClient =
      mock(CloseableHttpClient.class, withSettings().serializable());

  private final EvaluateSafeBrowsingFn safeBrowsingFn =
      new EvaluateSafeBrowsingFn(
          "API_KEY",
          new Retrier(new FakeSleeper(new FakeClock()), 1),
          Suppliers.ofInstance(mockHttpClient));

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private static Subdomain createSubdomain(String url) {
    return Subdomain.create(url, REPO_ID, REGISTRAR_ID, REGISTRAR_EMAIL);
  }

  private KV<Subdomain, ThreatMatch> getKv(String url) {
    Subdomain subdomain = createSubdomain(url);
    return KV.of(subdomain, THREAT_MATCH_MAP.get(subdomain));
  }

  @BeforeAll
  static void beforeAll() {
    ImmutableMap.Builder<Subdomain, ThreatMatch> builder = new ImmutableMap.Builder<>();
    THREAT_MAP
        .entrySet()
        .forEach(
            kv ->
                builder.put(
                    createSubdomain(kv.getKey()), ThreatMatch.create(kv.getValue(), kv.getKey())));
    THREAT_MATCH_MAP = builder.build();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    when(mockHttpClient.execute(any(HttpPost.class))).thenAnswer(new HttpResponder());
  }

  @Test
  void testSuccess_someBadDomains() throws Exception {
    ImmutableList<Subdomain> subdomains =
        ImmutableList.of(
            createSubdomain("111.com"),
            createSubdomain("hooli.com"),
            createSubdomain("party-night.net"),
            createSubdomain("anti-anti-anti-virus.dev"),
            createSubdomain("no-email.com"));
    PCollection<KV<Subdomain, ThreatMatch>> threats =
        pipeline
            .apply(Create.of(subdomains).withCoder(SerializableCoder.of(Subdomain.class)))
            .apply(ParDo.of(safeBrowsingFn));

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
    ImmutableList<Subdomain> subdomains =
        ImmutableList.of(
            createSubdomain("hello_kitty.dev"),
            createSubdomain("555.com"),
            createSubdomain("goodboy.net"));
    PCollection<KV<Subdomain, ThreatMatch>> threats =
        pipeline
            .apply(Create.of(subdomains).withCoder(SerializableCoder.of(Subdomain.class)))
            .apply(ParDo.of(safeBrowsingFn));

    PAssert.that(threats).empty();
    pipeline.run().waitUntilFinish();
  }

  /**
   * A serializable {@link Answer} that returns a mock HTTP response based on the HTTP request's
   * content.
   */
  static class HttpResponder implements Answer<CloseableHttpResponse>, Serializable {
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
