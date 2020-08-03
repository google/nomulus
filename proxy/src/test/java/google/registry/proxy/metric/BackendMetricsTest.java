// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy.metric;

import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.proxy.TestUtils.makeHttpPostRequest;
import static google.registry.proxy.TestUtils.makeHttpResponse;

import com.google.common.collect.ImmutableSet;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BackendMetrics}. */
class BackendMetricsTest {

  private final String host = "host.tld";
  private final String certHash = "blah12345";
  private final String protocol = "frontend protocol";

  private final BackendMetrics metrics = new BackendMetrics();

  @BeforeEach
  void beforeEach() {
    metrics.resetMetrics();
  }

  @Test
  void testSuccess_oneRequest() {
    String content = "some content";
    FullHttpRequest request = makeHttpPostRequest(content, host, "/");
    metrics.requestSent(protocol, certHash, request.content().readableBytes());

    assertThat(BackendMetrics.requestsCounter)
        .hasValueForLabels(1, protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.requestBytes)
        .hasDataSetForLabels(ImmutableSet.of(content.length()), protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responsesCounter).hasNoOtherValues();
    assertThat(BackendMetrics.responseBytes).hasNoOtherValues();
    assertThat(BackendMetrics.latencyMs).hasNoOtherValues();
  }

  @Test
  void testSuccess_multipleRequests() {
    String content1 = "some content";
    String content2 = "some other content";
    FullHttpRequest request1 = makeHttpPostRequest(content1, host, "/");
    FullHttpRequest request2 = makeHttpPostRequest(content2, host, "/");
    metrics.requestSent(protocol, certHash, request1.content().readableBytes());
    metrics.requestSent(protocol, certHash, request2.content().readableBytes());

    assertThat(BackendMetrics.requestsCounter)
        .hasValueForLabels(2, protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.requestBytes)
        .hasDataSetForLabels(
            ImmutableSet.of(content1.length(), content2.length()), protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responsesCounter).hasNoOtherValues();
    assertThat(BackendMetrics.responseBytes).hasNoOtherValues();
    assertThat(BackendMetrics.latencyMs).hasNoOtherValues();
  }

  @Test
  void testSuccess_oneResponse() {
    String content = "some response";
    FullHttpResponse response = makeHttpResponse(content, HttpResponseStatus.OK);
    metrics.responseReceived(protocol, certHash, response, Duration.millis(5));

    assertThat(BackendMetrics.requestsCounter).hasNoOtherValues();
    assertThat(BackendMetrics.requestBytes).hasNoOtherValues();
    assertThat(BackendMetrics.responsesCounter)
        .hasValueForLabels(1, protocol, certHash, "200 OK")
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responseBytes)
        .hasDataSetForLabels(ImmutableSet.of(content.length()), protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(5), protocol, certHash)
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSuccess_multipleResponses() {
    String content1 = "some response";
    String content2 = "other response";
    String content3 = "a very bad response";
    FullHttpResponse response1 = makeHttpResponse(content1, HttpResponseStatus.OK);
    FullHttpResponse response2 = makeHttpResponse(content2, HttpResponseStatus.OK);
    FullHttpResponse response3 = makeHttpResponse(content3, HttpResponseStatus.BAD_REQUEST);
    metrics.responseReceived(protocol, certHash, response1, Duration.millis(5));
    metrics.responseReceived(protocol, certHash, response2, Duration.millis(8));
    metrics.responseReceived(protocol, certHash, response3, Duration.millis(2));

    assertThat(BackendMetrics.requestsCounter).hasNoOtherValues();
    assertThat(BackendMetrics.requestBytes).hasNoOtherValues();
    assertThat(BackendMetrics.responsesCounter)
        .hasValueForLabels(2, protocol, certHash, "200 OK")
        .and()
        .hasValueForLabels(1, protocol, certHash, "400 Bad Request")
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responseBytes)
        .hasDataSetForLabels(
            ImmutableSet.of(content1.length(), content2.length(), content3.length()),
            protocol,
            certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(5, 8, 2), protocol, certHash)
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSuccess_oneRequest_oneResponse() {
    String requestContent = "some request";
    String responseContent = "the only response";
    FullHttpRequest request = makeHttpPostRequest(requestContent, host, "/");
    FullHttpResponse response = makeHttpResponse(responseContent, HttpResponseStatus.OK);
    metrics.requestSent(protocol, certHash, request.content().readableBytes());
    metrics.responseReceived(protocol, certHash, response, Duration.millis(10));

    assertThat(BackendMetrics.requestsCounter)
        .hasValueForLabels(1, protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responsesCounter)
        .hasValueForLabels(1, protocol, certHash, "200 OK")
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.requestBytes)
        .hasDataSetForLabels(ImmutableSet.of(requestContent.length()), protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.responseBytes)
        .hasDataSetForLabels(ImmutableSet.of(responseContent.length()), protocol, certHash)
        .and()
        .hasNoOtherValues();
    assertThat(BackendMetrics.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(10), protocol, certHash)
        .and()
        .hasNoOtherValues();
  }
}
