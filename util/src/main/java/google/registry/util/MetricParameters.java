// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Function;
import javax.inject.Inject;

/**
 * Utility class to obtain labels for monitored resource of type {@code gke_container}.
 *
 * <p>Custom metrics collected by the proxy need to be associated with a {@link MonitoredResource}.
 * When running on GKE, the type is {@code gke_container}. The labels for this type are used to
 * group related metrics together, and to avoid out-of-order metrics writes. This class provides a
 * map of the labels where the values are either read from environment variables (pod and container
 * related labels) or queried from GCE metadata server (cluster and instance related labels).
 *
 * @see <a
 *     href="https://cloud.google.com/monitoring/custom-metrics/creating-metrics#which-resource">
 *     Creating Custom Metrics - Choosing a monitored resource type</a>
 * @see <a href="https://cloud.google.com/monitoring/api/resources#tag_gke_container">Monitored
 *     Resource Types - gke_container</a>
 * @see <a href="https://cloud.google.com/compute/docs/storing-retrieving-metadata#querying">Storing
 *     and Retrieving Instance Metadata - Getting metadata</a>
 * @see <a
 *     href="https://kubernetes.io/docs/tasks/inject-data-application/environment-variable-expose-pod-information/">
 *     Expose Pod Information to Containers Through Environment Variables </a>
 */
public class MetricParameters {

  // Environment variable names, defined in the GKE deployment pod spec.
  static final String NAMESPACE_ID_ENV = "NAMESPACE_ID";
  static final String POD_ID_ENV = "POD_ID";
  static final String CONTAINER_NAME_ENV = "CONTAINER_NAME";

  // GCE metadata server URLs to retrieve instance related information.
  private static final String GCE_METADATA_URL_BASE = "http://metadata.google.internal/";
  static final String PROJECT_ID_PATH = "computeMetadata/v1/project/project-id";
  static final String CLUSTER_NAME_PATH = "computeMetadata/v1/instance/attributes/cluster-name";
  static final String INSTANCE_ID_PATH = "computeMetadata/v1/instance/id";
  static final String ZONE_PATH = "computeMetadata/v1/instance/zone";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Map<String, String> envVarMap;
  private final Function<String, HttpURLConnection> connectionFactory;

  MetricParameters(
      Map<String, String> envVarMap, Function<String, HttpURLConnection> connectionFactory) {
    this.envVarMap = envVarMap;
    this.connectionFactory = connectionFactory;
  }

  @Inject
  MetricParameters() {
    this(ImmutableMap.copyOf(System.getenv()), MetricParameters::gceConnectionFactory);
  }

  private static HttpURLConnection gceConnectionFactory(String path) {
    String url = GCE_METADATA_URL_BASE + path;
    try {
      HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
      connection.setRequestMethod("GET");
      // The metadata server requires this header to be set when querying from a GCE instance.
      connection.setRequestProperty("Metadata-Flavor", "Google");
      connection.setDoOutput(true);
      return connection;
    } catch (URISyntaxException | MalformedURLException e) {
      throw new RuntimeException(String.format("Incorrect GCE metadata server URL: %s", url), e);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Cannot connect to GCE metadata server: %s", url), e);
    }
  }

  private String readEnvVar(String envVar) {
    return envVarMap.getOrDefault(envVar, "");
  }

  private String readGceMetadata(String path) {
    String value = "";
    HttpURLConnection connection = connectionFactory.apply(path);
    try {
      connection.connect();
      int responseCode = connection.getResponseCode();
      if (responseCode < 200 || responseCode > 299) {
        logger.atWarning().log(
            "Got an error response: %d\n%s",
            responseCode,
            CharStreams.toString(new InputStreamReader(connection.getErrorStream(), UTF_8)));
      } else {
        value = CharStreams.toString(new InputStreamReader(connection.getInputStream(), UTF_8));
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot obtain GCE metadata from path %s", path);
    }
    return value;
  }

  public ImmutableMap<String, String> makeLabelsMap() {
    // The zone metadata is in the form of "projects/<PROJECT_NUMERICAL_ID>/zones/<ZONE_NAME>".
    // We only need the last part after the slash.
    String fullZone = readGceMetadata(ZONE_PATH);
    String zone;
    String[] fullZoneArray = fullZone.split("/", -1);
    if (fullZoneArray.length < 4) {
      logger.atWarning().log("Zone %s is valid.", fullZone);
      // This will make the metric report throw, but it happens in a different thread and will not
      // kill the whole application.
      zone = "";
    } else {
      zone = fullZoneArray[3];
    }
    return new ImmutableMap.Builder<String, String>()
        .put("project_id", readGceMetadata(PROJECT_ID_PATH))
        .put("cluster_name", readGceMetadata(CLUSTER_NAME_PATH))
        .put("namespace_id", readEnvVar(NAMESPACE_ID_ENV))
        .put("instance_id", readGceMetadata(INSTANCE_ID_PATH))
        .put("pod_id", readEnvVar(POD_ID_ENV))
        .put("container_name", readEnvVar(CONTAINER_NAME_ENV))
        .put("zone", zone)
        .build();
  }
}
