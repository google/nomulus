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

package google.registry.request;

import static com.google.monitoring.metrics.EventMetric.DEFAULT_FITTER;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.request.auth.AuthLevel;
import org.joda.time.Duration;

class RequestMetrics {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableSet<LabelDescriptor> REQUEST_LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("path", "target path"),
          LabelDescriptor.create("method", "request method"),
          LabelDescriptor.create("authLevel", "how the user was authenticated"),
          LabelDescriptor.create("success", "whether the request succeeded"));

  static final EventMetric requestDurationMetric =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/request/processing_time",
              "Action processing time",
              "milliseconds",
              REQUEST_LABEL_DESCRIPTORS,
              DEFAULT_FITTER);

  public RequestMetrics() {}

  public void record(
      Duration duration, String path, Action.Method method, AuthLevel authLevel, boolean success) {
    requestDurationMetric.record(
        duration.getMillis(),
        path,
        String.valueOf(method),
        String.valueOf(authLevel),
        String.valueOf(success));
    logger.atInfo().log(
        "Action called for path=%s, method=%s, authLevel=%s, success=%s. Took: %.3fs",
        path, method, authLevel, success, duration.getMillis() / 1000d);
  }
}
