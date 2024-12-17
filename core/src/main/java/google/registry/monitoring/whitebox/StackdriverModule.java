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

package google.registry.monitoring.whitebox;

import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.monitoring.metrics.MetricReporter;
import com.google.monitoring.metrics.MetricWriter;
import com.google.monitoring.metrics.stackdriver.StackdriverWriter;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.MetricParameters;
import google.registry.util.RegistryEnvironment;
import javax.inject.Named;
import javax.inject.Singleton;
import org.joda.time.Duration;

/** Dagger module for Google Stackdriver service connection objects. */
@Module
public final class StackdriverModule {

  private StackdriverModule() {}

  // We need a fake GCE zone to appease Stackdriver's resource model.
  // TODO(b/265973059): Switch to resource type "gke_container".
  private static final String SPOOFED_GCE_ZONE = "us-central1-f";

  // We cannot use a static fake intance ID which is shared by all instances, because metrics might
  // be flushed to stackdriver with delays, which lead to time inversion erros when another instance
  // has already written a data point at a later time.
  @Singleton
  @Provides
  @Named("spoofedGceInstanceId")
  static String providesSpoofedGceInstanceId(Clock clock) {
    return clock.nowUtc().toString();
  }

  @Provides
  static Monitoring provideMonitoring(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    return new Monitoring.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .build();
  }

  @Provides
  static MetricWriter provideMetricWriter(
      Monitoring monitoringClient,
      Lazy<MetricParameters> gkeParameters,
      @Config("projectId") String projectId,
      @Config("stackdriverMaxQps") int maxQps,
      @Config("stackdriverMaxPointsPerRequest") int maxPointsPerRequest,
      @Named("spoofedGceInstanceId") String instanceId) {
    MonitoredResource resource =
        RegistryEnvironment.isOnJetty()
            ? new MonitoredResource()
                .setType("gke_container")
                .setLabels(gkeParameters.get().makeLabelsMap())
            :
            // The MonitoredResource for GAE apps is not writable (and missing fields anyway) so we
            // just use the gce_instance resource type instead.
            new MonitoredResource()
                .setType("gce_instance")
                .setLabels(
                    ImmutableMap.of(
                        // The "zone" field MUST be a valid GCE zone, so we fake one.
                        "zone", SPOOFED_GCE_ZONE, "instance_id", instanceId));

    return new StackdriverWriter(
        monitoringClient, projectId, resource, maxQps, maxPointsPerRequest);
  }

  @Provides
  static MetricReporter provideMetricReporter(
      MetricWriter metricWriter, @Config("metricsWriteInterval") Duration writeInterval) {
    return new MetricReporter(
        metricWriter,
        writeInterval.getStandardSeconds(),
        new ThreadFactoryBuilder().setDaemon(true).build());
  }
}
