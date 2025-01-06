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

package google.registry.flows;

import static com.google.monitoring.metrics.EventMetric.DEFAULT_FITTER;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.monitoring.whitebox.CheckApiMetric;
import google.registry.monitoring.whitebox.CheckApiMetric.Availability;
import google.registry.monitoring.whitebox.CheckApiMetric.Tier;
import javax.inject.Inject;

/** Helpers for updating domain check metrics. */
public class CheckApiMetrics {

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("tier", "Price tier of the domain name."),
          LabelDescriptor.create("availability", "Availability of the domain name."),
          LabelDescriptor.create("status", "The return status of the check."));

  private static final IncrementableMetric requests =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/check_api/requests", "Count of CheckApi Requests", "count", LABEL_DESCRIPTORS);

  private static final EventMetric processingTime =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/check_api/processing_time",
              "CheckApi Processing Time",
              "milliseconds",
              LABEL_DESCRIPTORS,
              DEFAULT_FITTER);

  @Inject
  CheckApiMetrics() {}

  public void incrementCheckApiRequest(CheckApiMetric metric) {
    requests.increment(
        metric.tier().map(Tier::getDisplayLabel).orElse(""),
        metric.availability().map(Availability::getDisplayLabel).orElse(""),
        metric.status().getDisplayLabel());
  }

  public void recordProcessingTime(CheckApiMetric metric) {
    long elapsedTime = metric.endTimestamp().getMillis() - metric.startTimestamp().getMillis();
    processingTime.record(
        elapsedTime,
        metric.tier().map(Tier::getDisplayLabel).orElse(""),
        metric.availability().map(Availability::getDisplayLabel).orElse(""),
        metric.status().getDisplayLabel());
  }
}
