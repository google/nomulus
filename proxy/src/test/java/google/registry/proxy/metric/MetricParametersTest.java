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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.proxy.metric.MetricParameters.CLUSTER_NAME_PATH;
import static google.registry.proxy.metric.MetricParameters.CONTAINER_NAME_ENV;
import static google.registry.proxy.metric.MetricParameters.INSTANCE_ID_PATH;
import static google.registry.proxy.metric.MetricParameters.NAMESPACE_ID_ENV;
import static google.registry.proxy.metric.MetricParameters.POD_ID_ENV;
import static google.registry.proxy.metric.MetricParameters.PROJECT_ID_PATH;
import static google.registry.proxy.metric.MetricParameters.ZONE_PATH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MetricParameters}. */
class MetricParametersTest {

  private static final HashMap<String, String> RESULTS = new HashMap<>();

  private final HttpURLConnection projectIdConnection = mock(HttpURLConnection.class);
  private final HttpURLConnection clusterNameConnection = mock(HttpURLConnection.class);
  private final HttpURLConnection instanceIdConnection = mock(HttpURLConnection.class);
  private final HttpURLConnection zoneConnection = mock(HttpURLConnection.class);
  private final ImmutableMap<String, HttpURLConnection> mockConnections =
      ImmutableMap.of(
          PROJECT_ID_PATH,
          projectIdConnection,
          CLUSTER_NAME_PATH,
          clusterNameConnection,
          INSTANCE_ID_PATH,
          instanceIdConnection,
          ZONE_PATH,
          zoneConnection);
  private final HashMap<String, String> fakeEnvVarMap = new HashMap<>();
  private final Function<String, HttpURLConnection> fakeConnectionFactory = mockConnections::get;

  private final MetricParameters metricParameters =
      new MetricParameters(fakeEnvVarMap, fakeConnectionFactory);

  private static InputStream makeInputStreamFromString(String input) {
    return new ByteArrayInputStream(input.getBytes(UTF_8));
  }

  @BeforeEach
  void beforeEach() throws Exception {
    fakeEnvVarMap.put(NAMESPACE_ID_ENV, "some-namespace");
    fakeEnvVarMap.put(POD_ID_ENV, "some-pod");
    fakeEnvVarMap.put(CONTAINER_NAME_ENV, "some-container");
    when(projectIdConnection.getInputStream())
        .thenReturn(makeInputStreamFromString("some-project"));
    when(clusterNameConnection.getInputStream())
        .thenReturn(makeInputStreamFromString("some-cluster"));
    when(instanceIdConnection.getInputStream())
        .thenReturn(makeInputStreamFromString("some-instance"));
    when(zoneConnection.getInputStream())
        .thenReturn(makeInputStreamFromString("projects/some-project/zones/some-zone"));
    for (Entry<String, HttpURLConnection> entry : mockConnections.entrySet()) {
      when(entry.getValue().getResponseCode()).thenReturn(200);
    }
    RESULTS.put("project_id", "some-project");
    RESULTS.put("cluster_name", "some-cluster");
    RESULTS.put("namespace_id", "some-namespace");
    RESULTS.put("instance_id", "some-instance");
    RESULTS.put("pod_id", "some-pod");
    RESULTS.put("container_name", "some-container");
    RESULTS.put("zone", "some-zone");
  }

  @Test
  void testSuccess() {
    assertThat(metricParameters.makeLabelsMap()).isEqualTo(ImmutableMap.copyOf(RESULTS));
  }

  @Test
  void testSuccess_missingEnvVar() {
    fakeEnvVarMap.remove(POD_ID_ENV);
    RESULTS.put("pod_id", "");
    assertThat(metricParameters.makeLabelsMap()).isEqualTo(ImmutableMap.copyOf(RESULTS));
  }

  @Test
  void testSuccess_malformedZone() throws Exception {
    when(zoneConnection.getInputStream()).thenReturn(makeInputStreamFromString("some-zone"));
    RESULTS.put("zone", "");
    assertThat(metricParameters.makeLabelsMap()).isEqualTo(ImmutableMap.copyOf(RESULTS));
  }

  @Test
  void testSuccess_errorResponseCode() throws Exception {
    when(projectIdConnection.getResponseCode()).thenReturn(404);
    when(projectIdConnection.getErrorStream())
        .thenReturn(makeInputStreamFromString("some error message"));
    RESULTS.put("project_id", "");
    assertThat(metricParameters.makeLabelsMap()).isEqualTo(ImmutableMap.copyOf(RESULTS));
  }

  @Test
  void testSuccess_connectionError() throws Exception {
    InputStream fakeInputStream = mock(InputStream.class);
    when(projectIdConnection.getInputStream()).thenReturn(fakeInputStream);
    when(fakeInputStream.read(any(byte[].class), anyInt(), anyInt()))
        .thenThrow(new IOException("some exception"));
    RESULTS.put("project_id", "");
    assertThat(metricParameters.makeLabelsMap()).isEqualTo(ImmutableMap.copyOf(RESULTS));
  }
}
