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
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.joda.time.Duration;

/** Dagger module for monitoring and Google Stackdriver service connection objects. */
@Module
public final class StackdriverModule {

  private StackdriverModule() {}

  // We cannot use a static fake instance ID which is shared by all instances, because metrics might
  // be flushed to stackdriver with delays, which lead to time inversion errors when another
  // instance has already written a data point at a later time.
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
      @Config("stackdriverMaxPointsPerRequest") int maxPointsPerRequest) {
    MonitoredResource resource =
        new MonitoredResource()
            .setType("gke_container")
            .setLabels(gkeParameters.get().makeLabelsMap());
    return new StackdriverWriter(
        monitoringClient, projectId, resource, maxQps, maxPointsPerRequest);
  }

  @Provides
  static MetricReporter provideMetricReporter(
      MetricWriter metricWriter, @Config("metricsWriteInterval") Duration writeInterval,
      JvmMetrics unusedJvmMetrics) {
    jvmMetrics.register();

    return new MetricReporter(
        metricWriter,
        writeInterval.getStandardSeconds(),
        new ThreadFactoryBuilder().setDaemon(true).build());
  }
}
