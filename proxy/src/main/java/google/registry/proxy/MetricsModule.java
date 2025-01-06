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

package google.registry.proxy;

import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.monitoring.metrics.MetricReporter;
import com.google.monitoring.metrics.MetricWriter;
import com.google.monitoring.metrics.stackdriver.StackdriverWriter;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.proxy.ProxyConfig.Environment;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.MetricParameters;
import javax.inject.Singleton;

/** Module that provides necessary bindings to instantiate a {@link MetricReporter} */
@Module
public class MetricsModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Singleton
  @Provides
  static Monitoring provideMonitoring(GoogleCredentialsBundle credentialsBundle,
      ProxyConfig config) {
    return new Monitoring.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(config.projectId)
        .build();
  }

  @Singleton
  @Provides
  static MetricWriter provideMetricWriter(
      Monitoring monitoringClient, MonitoredResource monitoredResource, ProxyConfig config) {
    return new StackdriverWriter(
        monitoringClient,
        config.projectId,
        monitoredResource,
        config.metrics.stackdriverMaxQps,
        config.metrics.stackdriverMaxPointsPerRequest);
  }

  @Singleton
  @Provides
  static MetricReporter provideMetricReporter(MetricWriter metricWriter, ProxyConfig config) {
    return new MetricReporter(
        metricWriter,
        config.metrics.writeIntervalSeconds,
        new ThreadFactoryBuilder().setDaemon(true).build());
  }

  /**
   * Provides a {@link MonitoredResource} appropriate for environment tha proxy runs in.
   *
   * <p>When running locally, the type of the monitored resource is set to {@code global}, otherwise
   * it is {@code gke_container}.
   *
   * @see <a
   *     href="https://cloud.google.com/monitoring/custom-metrics/creating-metrics#which-resource">
   *     Choosing a monitored resource type</a>
   */
  @Singleton
  @Provides
  static MonitoredResource provideMonitoredResource(
      Environment env, ProxyConfig config, MetricParameters metricParameters) {
    MonitoredResource monitoredResource = new MonitoredResource();
    if (env == Environment.LOCAL) {
      monitoredResource
          .setType("global")
          .setLabels(ImmutableMap.of("project_id", config.projectId));
    } else {
      monitoredResource.setType("gke_container").setLabels(metricParameters.makeLabelsMap());
    }
    logger.atInfo().log("Monitored resource: %s", monitoredResource);
    return monitoredResource;
  }

  @Singleton
  @Component(modules = {MetricsModule.class, ProxyModule.class})
  interface MetricsComponent {
    MetricReporter metricReporter();
  }
}
