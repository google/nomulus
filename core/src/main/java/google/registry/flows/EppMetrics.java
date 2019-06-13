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

package google.registry.flows;

import static com.google.monitoring.metrics.EventMetric.DEFAULT_FITTER;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.monitoring.whitebox.EppMetric;
import javax.inject.Inject;

/** EPP Instrumentation. */
public class EppMetrics {

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_BY_REGISTRAR =
      ImmutableSet.of(
          LabelDescriptor.create("command", "The name of the command."),
          LabelDescriptor.create("client_id", "The name of the client."),
          LabelDescriptor.create("status", "The return status of the command."));

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_BY_TLD =
      ImmutableSet.of(
          LabelDescriptor.create("command", "The name of the command."),
          LabelDescriptor.create("tld", "The TLD acted on by the command (if applicable)."),
          LabelDescriptor.create("status", "The return status of the command."));

  private static final IncrementableMetric eppRequestsByRegistrar =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/epp/requests",
              "Count of EPP Requests By Registrar",
              "count",
              LABEL_DESCRIPTORS_BY_REGISTRAR);

  private static final IncrementableMetric eppRequestsByTld =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/epp/requests_by_tld",
              "Count of EPP Requests By TLD",
              "count",
              LABEL_DESCRIPTORS_BY_TLD);

  private static final EventMetric processingTimeByRegistrar =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/epp/processing_time",
              "EPP Processing Time By Registrar",
              "milliseconds",
              LABEL_DESCRIPTORS_BY_REGISTRAR,
              DEFAULT_FITTER);

  private static final EventMetric processingTimeByTld =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/epp/processing_time_by_tld",
              "EPP Processing Time By TLD",
              "milliseconds",
              LABEL_DESCRIPTORS_BY_TLD,
              DEFAULT_FITTER);

  @Inject
  public EppMetrics() {}

  /**
   * Increments the counters which tracks EPP requests.
   *
   * @see EppController
   * @see FlowRunner
   */
  public void incrementEppRequests(EppMetric metric) {
    String eppStatusCode =
        metric.getStatus().isPresent() ? String.valueOf(metric.getStatus().get().code) : "";
    eppRequestsByRegistrar.increment(
        metric.getCommandName().orElse(""), metric.getClientId().orElse(""), eppStatusCode);
    eppRequestsByTld.increment(
        metric.getCommandName().orElse(""), metric.getTld().orElse(""), eppStatusCode);
  }

  /** Records the server-side processing time for an EPP request. */
  public void recordProcessingTime(EppMetric metric) {
    String eppStatusCode =
        metric.getStatus().isPresent() ? String.valueOf(metric.getStatus().get().code) : "";
    long processingTime =
        metric.getEndTimestamp().getMillis() - metric.getStartTimestamp().getMillis();
    processingTimeByRegistrar.record(
        processingTime,
        metric.getCommandName().orElse(""),
        metric.getClientId().orElse(""),
        eppStatusCode);
    processingTimeByTld.record(
        processingTime,
        metric.getCommandName().orElse(""),
        metric.getTld().orElse(""),
        eppStatusCode);
  }
}
