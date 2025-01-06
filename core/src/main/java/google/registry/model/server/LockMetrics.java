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

package google.registry.model.server;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.DistributionFitter;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.ExponentialFitter;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.model.server.Lock.LockState;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/** Metrics for lock contention. */
class LockMetrics {

  private static final ImmutableSet<LabelDescriptor> REQUEST_LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("tld", "TLD"),
          LabelDescriptor.create("resource", "resource name"),
          LabelDescriptor.create(
              "state", "The existing lock state (before attempting to acquire)."));

  private static final ImmutableSet<LabelDescriptor> RELEASE_LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("tld", "TLD"),
          LabelDescriptor.create("resource", "resource name"));

  // Finer-grained fitter than the DEFAULT_FITTER, allows values between 10 and 10*2^20, which
  // gives almost 3 hours.
  private static final DistributionFitter EXPONENTIAL_FITTER =
      ExponentialFitter.create(20, 2.0, 10.0);

  private static final IncrementableMetric lockRequestsMetric =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/lock/acquire_lock_requests",
              "Count of lock acquisition attempts",
              "count",
              REQUEST_LABEL_DESCRIPTORS);

  private static final EventMetric lockLifetimeMetric =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/lock/lock_duration",
              "Lock lifetime",
              "milliseconds",
              RELEASE_LABEL_DESCRIPTORS,
              EXPONENTIAL_FITTER);

  void recordAcquire(String resourceName, @Nullable String tld, LockState state) {
    lockRequestsMetric.increment(String.valueOf(tld), resourceName, state.name());
  }

  void recordRelease(String resourceName, @Nullable String tld, Duration duration) {
    lockLifetimeMetric.record(duration.getMillis(), String.valueOf(tld), resourceName);
  }
}
