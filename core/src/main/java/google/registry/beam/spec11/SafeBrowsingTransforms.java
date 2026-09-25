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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Utilities and Beam {@code PTransforms} for interacting with the SafeBrowsing API. */
public class SafeBrowsingTransforms {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** The URL to send SafeBrowsing Lookup API calls (threatMatches:find) to. */
  private static final String SAFE_BROWSING_URL =
      "https://safebrowsing.googleapis.com/v4/threatMatches:find";

  /** The URL to fetch SafeBrowsing threat list updates (threatListUpdates:fetch) from. */
  private static final String THREAT_LIST_UPDATES_URL =
      "https://safebrowsing.googleapis.com/v4/threatListUpdates:fetch";

  /** The threat types evaluated for Spec11 reporting. */
  static final ImmutableList<String> THREAT_TYPES =
      ImmutableList.of("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE");

  /** Computes the SHA-256 hash for a given URL or host/path expression. */
  public static byte[] computeSha256(String expression) {
    return Hashing.sha256().hashString(expression, UTF_8).asBytes();
  }

  /** Converts the first 4 bytes of a SHA-256 hash into an integer. */
  public static int getPrefixInt(byte[] sha256Bytes) {
    return ByteBuffer.wrap(sha256Bytes).getInt();
  }

  /**
   * {@link DoFn} fetching threat list hash prefixes from the SafeBrowsing Update API.
   *
   * <p>SafeBrowsing provides an endpoint returning 4-byte hash prefixes of harmful URLs. This
   * serves as an in-memory prefix filter; domains whose hashes do not match any prefix are
   * guaranteed clean and require no API calls (similar to a bloom filter). For the small fraction
   * of domains that match a prefix (actual threats or hash collisions), we confirm them via the
   * Lookup API.
   */
  public static class FetchThreatListPrefixesFn extends DoFn<String, int[]> {

    private final Retrier retrier;
    private final Supplier<CloseableHttpClient> closeableHttpClientSupplier;

    FetchThreatListPrefixesFn(Retrier retrier) {
      this(retrier, (Supplier<CloseableHttpClient> & Serializable) HttpClients::createDefault);
    }

    @VisibleForTesting
    FetchThreatListPrefixesFn(Retrier retrier, Supplier<CloseableHttpClient> clientSupplier) {
      this.retrier = retrier;
      this.closeableHttpClientSupplier = clientSupplier;
    }

    @ProcessElement
    public void processElement(@Element String apiKey, OutputReceiver<int[]> output) {
      try {
        URIBuilder uriBuilder = new URIBuilder(THREAT_LIST_UPDATES_URL);
        uriBuilder.addParameter("key", apiKey);

        HttpPost httpPost = new HttpPost(uriBuilder.build());
        JSONObject requestBody = createFetchRequestBody();
        httpPost.setEntity(
            new ByteArrayEntity(
                requestBody.toString().getBytes(UTF_8), ContentType.APPLICATION_JSON));

        int[] prefixes =
            retrier.callWithRetry(
                () -> {
                  try (CloseableHttpClient client = closeableHttpClientSupplier.get();
                      CloseableHttpResponse response = client.execute(httpPost)) {
                    return processSafeBrowsingFetchResponse(response);
                  }
                },
                IOException.class);
        output.output(prefixes);
      } catch (URISyntaxException | JSONException e) {
        throw new RuntimeException("Caught exception fetching threat list prefixes.", e);
      }
    }
  }

  private static JSONObject createFetchRequestBody() throws JSONException {
    JSONArray listUpdateRequests = new JSONArray();
    for (String threatType : THREAT_TYPES) {
      listUpdateRequests.put(
          new JSONObject()
              .put("threatType", threatType)
              .put("platformType", "ANY_PLATFORM")
              .put("threatEntryType", "URL")
              .put(
                  "constraints",
                  new JSONObject().put("supportedCompressions", new JSONArray().put("RAW"))));
    }
    return new JSONObject()
        .put(
            "client",
            new JSONObject().put("clientId", "domainregistry").put("clientVersion", "0.0.1"))
        .put("listUpdateRequests", listUpdateRequests);
  }

  /**
   * Fetches and unpacks threat-match hash prefixes from a SafeBrowsing response.
   *
   * <p>SafeBrowsing provides an API endpoint (threatListUpdates) that includes a base64 string
   * representing hash prefixes. When we decode that base64 string, we get a byte array representing
   * the concatenation of 4-byte prefixes of sha256 hashes of harmful domains.
   *
   * <p>We represent the result as an int array, with each 4-byte int representing the first four
   * bytes of a sha256 hash of a harmful domain.
   */
  private static int[] processSafeBrowsingFetchResponse(CloseableHttpResponse response)
      throws IOException, JSONException {
    int statusCode = response.getStatusLine().getStatusCode();
    if (statusCode != SC_OK) {
      throw new IOException(
          String.format(
              "Got unexpected status code %s from threatListUpdates response.", statusCode));
    }
    try (InputStreamReader reader =
        new InputStreamReader(response.getEntity().getContent(), UTF_8)) {
      JSONObject responseBody = new JSONObject(CharStreams.toString(reader));
      JSONArray listUpdateResponses = responseBody.optJSONArray("listUpdateResponses");
      if (listUpdateResponses == null || listUpdateResponses.isEmpty()) {
        throw new IOException("No value for listUpdateResponses in threatListUpdates response");
      }
      // We expect 10-20 MB of data, so initialize the stream with a relatively large size
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(10 * 1024 * 1024);
      // We ask SafeBrowsing for multiple threat types, and they return a different object for each
      for (int i = 0; i < listUpdateResponses.length(); i++) {
        JSONArray additions = listUpdateResponses.getJSONObject(i).optJSONArray("additions");
        if (additions != null) {
          for (int j = 0; j < additions.length(); j++) {
            JSONObject rawHashes = additions.getJSONObject(j).optJSONObject("rawHashes");
            if (rawHashes != null) {
              int prefixSize = rawHashes.optInt("prefixSize");
              checkArgument(prefixSize == 4, "Expected prefixSize of 4, got %s", prefixSize);
              String base64 = rawHashes.optString("rawHashes", "");
              if (!base64.isEmpty()) {
                bytes.writeBytes(Base64.getDecoder().decode(base64));
              }
            }
          }
        }
      }
      byte[] byteArray = bytes.toByteArray();
      if (byteArray.length == 0 || byteArray.length % 4 != 0) {
        throw new IOException(
            String.format(
                "Expected prefix byte array length to be a positive multiple of 4, got %d",
                byteArray.length));
      }
      int[] prefixes = new int[byteArray.length / 4];
      ByteBuffer.wrap(byteArray).asIntBuffer().get(prefixes);
      // Sort the array to normalize signed integer order and enable binary search
      Arrays.sort(prefixes);
      // Remove any duplicates to reduce broadcast size
      return removeDuplicates(prefixes);
    }
  }

  /** Removes duplicates from an already sorted prefix array in-place. */
  private static int[] removeDuplicates(int[] prefixes) {
    int uniquePrefix = 0;
    for (int i = 0; i < prefixes.length; i++) {
      if (i == 0 || prefixes[i] != prefixes[i - 1]) {
        prefixes[uniquePrefix] = prefixes[i];
        uniquePrefix++;
      }
    }
    if (uniquePrefix < prefixes.length) {
      prefixes = Arrays.copyOf(prefixes, uniquePrefix);
    }
    return prefixes;
  }

  /**
   * {@link DoFn} mapping a {@link DomainNameInfo} to its evaluation report from SafeBrowsing.
   *
   * <p>Incoming domains are first checked locally against the 4-byte hash prefixes side input. Any
   * domains matching a prefix (potential threats or hash collisions) are then confirmed in batches
   * using the SafeBrowsing Lookup API.
   *
   * @see <a href=https://developers.google.com/safe-browsing/v4/lookup-api>Lookup API</a>
   */
  static class EvaluateSafeBrowsingFn
      extends DoFn<DomainNameInfo, KV<DomainNameInfo, ThreatMatch>> {

    /**
     * Max number of urls we can check in a single query.
     *
     * <p>The actual max is 500, but we leave a small gap in case of concurrency errors.
     */
    private static final int BATCH_SIZE = 490;

    /** Provides the SafeBrowsing API key at runtime. */
    private final String apiKey;

    private final Clock clock;

    /**
     * Maps a domain's {@code domainName} to its corresponding {@link DomainNameInfo} to facilitate
     * batching SafeBrowsing API requests.
     */
    private final Map<String, DomainNameInfo> domainNameInfoBuffer =
        new LinkedHashMap<>(BATCH_SIZE);

    /**
     * Provides the HTTP client we use to interact with the SafeBrowsing API.
     *
     * <p>This is a supplier to enable mocking out the connection in unit tests while maintaining a
     * serializable field.
     */
    private final Supplier<CloseableHttpClient> closeableHttpClientSupplier;

    /** Retries on receiving transient failures such as {@link IOException}. */
    private final Retrier retrier;

    /** Side input providing the sorted 4-byte hash prefixes of harmful domains. */
    private final PCollectionView<int[]> prefixesView;

    /** Cached reference to the hash prefix side input to avoid repeated lookups per element. */
    private transient @Nullable int[] prefixes;

    /**
     * Constructs a {@link EvaluateSafeBrowsingFn} with a given API key.
     *
     * <p>We need to dual-cast the closeableHttpClientSupplier lambda because all {@code DoFn}
     * member variables need to be serializable. The (Supplier & Serializable) dual cast is safe
     * because class methods are generally serializable, especially a static function such as {@link
     * HttpClients#createDefault()}.
     */
    EvaluateSafeBrowsingFn(
        String apiKey, Retrier retrier, Clock clock, PCollectionView<int[]> prefixesView) {
      this(
          apiKey,
          retrier,
          clock,
          (Supplier<CloseableHttpClient> & Serializable) HttpClients::createDefault,
          prefixesView);
    }

    /**
     * Constructs a {@link EvaluateSafeBrowsingFn}, allowing us to swap out the HTTP client supplier
     * for testing.
     *
     * @param clientSupplier a serializable CloseableHttpClient supplier
     */
    @VisibleForTesting
    EvaluateSafeBrowsingFn(
        String apiKey,
        Retrier retrier,
        Clock clock,
        Supplier<CloseableHttpClient> clientSupplier,
        PCollectionView<int[]> prefixesView) {
      this.apiKey = apiKey;
      this.retrier = retrier;
      this.clock = clock;
      this.closeableHttpClientSupplier = clientSupplier;
      this.prefixesView = prefixesView;
    }

    /** Evaluates any buffered {@link DomainNameInfo} objects upon completing the bundle. */
    @FinishBundle
    public void finishBundle(FinishBundleContext context) {
      if (!domainNameInfoBuffer.isEmpty()) {
        ImmutableSet<KV<DomainNameInfo, ThreatMatch>> results = evaluateAndFlush();
        results.forEach(
            kv -> {
              // The Apache Beam API requires org.joda.time.Instant here.
              @SuppressWarnings("UnnecessarilyFullyQualified")
              org.joda.time.Instant timestamp =
                  org.joda.time.Instant.ofEpochMilli(clock.nowMillis());
              context.output(kv, timestamp, GlobalWindow.INSTANCE);
            });
      }
    }

    /**
     * Checks each domain against the hash prefix side input. Matching domains (potential threats or
     * collisions) are buffered until reaching {@link #BATCH_SIZE} and then evaluated in bulk via
     * the SafeBrowsing Lookup API.
     */
    @ProcessElement
    public void processElement(ProcessContext context) {
      if (prefixes == null) {
        prefixes = context.sideInput(prefixesView);
      }
      DomainNameInfo domainNameInfo = context.element();
      // The canonical domain form is e.g. "mydomain.tld/". See
      // https://developers.google.com/safe-browsing/v4/urls-hashing for more details
      byte[] hash = computeSha256(Ascii.toLowerCase(domainNameInfo.domainName()) + "/");
      int prefixInt = getPrefixInt(hash);
      if (Arrays.binarySearch(prefixes, prefixInt) < 0) {
        // The domain's prefix does not match any threat prefix; it cannot be marked as harmful
        return;
      }
      domainNameInfoBuffer.put(domainNameInfo.domainName(), domainNameInfo);
      if (domainNameInfoBuffer.size() >= BATCH_SIZE) {
        ImmutableSet<KV<DomainNameInfo, ThreatMatch>> results = evaluateAndFlush();
        results.forEach(context::output);
      }
    }

    /**
     * Evaluates all {@link DomainNameInfo} objects in the buffer and returns a list of key-value
     * pairs from {@link DomainNameInfo} to its SafeBrowsing report.
     *
     * <p>If a {@link DomainNameInfo} is safe according to the API, it will not emit a report.
     */
    private ImmutableSet<KV<DomainNameInfo, ThreatMatch>> evaluateAndFlush() {
      ImmutableSet.Builder<KV<DomainNameInfo, ThreatMatch>> resultBuilder =
          new ImmutableSet.Builder<>();
      try {
        URIBuilder uriBuilder = new URIBuilder(SAFE_BROWSING_URL);
        // Add the API key param
        uriBuilder.addParameter("key", apiKey);

        HttpPost httpPost = new HttpPost(uriBuilder.build());

        JSONObject requestBody = createRequestBody();
        httpPost.setEntity(
            new ByteArrayEntity(
                requestBody.toString().getBytes(UTF_8), ContentType.APPLICATION_JSON));
        // Retry transient exceptions such as IOException
        retrier.callWithRetry(
            () -> {
              try (CloseableHttpClient client = closeableHttpClientSupplier.get();
                  CloseableHttpResponse response = client.execute(httpPost)) {
                processResponse(response, resultBuilder);
              }
            },
            IOException.class);
      } catch (URISyntaxException | JSONException e) {
        // Fail the pipeline on a parsing exception. This indicates the API likely changed.
        throw new RuntimeException("Caught parsing exception, failing pipeline.", e);
      } finally {
        // Flush the buffer
        domainNameInfoBuffer.clear();
      }
      return resultBuilder.build();
    }

    /** Creates a JSON object matching the request format for the SafeBrowsing API. */
    private JSONObject createRequestBody() throws JSONException {
      // Accumulate all domain names to evaluate.
      JSONArray threatArray = new JSONArray();
      for (String domainName : domainNameInfoBuffer.keySet()) {
        threatArray.put(new JSONObject().put("url", domainName));
      }
      // Construct the JSON request body
      return new JSONObject()
          .put(
              "client",
              new JSONObject().put("clientId", "domainregistry").put("clientVersion", "0.0.1"))
          .put(
              "threatInfo",
              new JSONObject()
                  .put("threatTypes", new JSONArray(THREAT_TYPES))
                  .put("platformTypes", new JSONArray().put("ANY_PLATFORM"))
                  .put("threatEntryTypes", new JSONArray().put("URL"))
                  .put("threatEntries", threatArray));
    }

    /**
     * Iterates through all threat matches in the API response and adds them to the {@code
     * resultBuilder}.
     */
    private void processResponse(
        CloseableHttpResponse response,
        ImmutableSet.Builder<KV<DomainNameInfo, ThreatMatch>> resultBuilder)
        throws IOException {
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != SC_OK) {
        throw new IOException(
            String.format("Got unexpected status code %s from response.", statusCode));
      }
      // Unpack the response body
      try (InputStreamReader reader =
          new InputStreamReader(response.getEntity().getContent(), UTF_8)) {
        JSONObject responseBody = new JSONObject(CharStreams.toString(reader));
        if (responseBody.isEmpty()) {
          logger.atInfo().log("Response was empty, no threats detected.");
        } else {
          // Emit all DomainNameInfos with their API results.
          JSONArray threatMatches = responseBody.getJSONArray("matches");
          for (int i = 0; i < threatMatches.length(); i++) {
            JSONObject match = threatMatches.getJSONObject(i);
            String url = match.getJSONObject("threat").getString("url");
            DomainNameInfo domainNameInfo = domainNameInfoBuffer.get(url);
            if (domainNameInfo == null) {
              throw new IOException(
                  String.format("Received threat match for domain not present in buffer: %s", url));
            }
            resultBuilder.add(
                KV.of(
                    domainNameInfo,
                    ThreatMatch.create(
                        match.getString("threatType"), domainNameInfo.domainName())));
          }
        }
      }
    }
  }
}
