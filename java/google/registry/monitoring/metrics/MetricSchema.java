// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.metrics;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;

/** The description of a metric's schema. */
@AutoValue
public abstract class MetricSchema {

  MetricSchema() {}

  /**
   * Returns an instance of {@link MetricSchema}.
   *
   * @param name must have a URL-like hierarchical name, for example "/cpu/utilization".
   * @param description a human readable description of the metric. Must not be blank.
   * @param valueDisplayName a human readable description of the metric's value. Must not be blank.
   * @param kind the kind of metric.
   * @param labels an ordered set of mandatory metric labels. For example, a metric may track error
   *     code as a label. If labels are set, corresponding label values must be provided when values
   *     are set. The set of labels may be empty.
   */
  static MetricSchema create(
      String name,
      String description,
      String valueDisplayName,
      Kind kind,
      ImmutableSet<LabelDescriptor> labels) {
    checkArgument(!name.isEmpty(), "Name must not be blank");
    checkArgument(!description.isEmpty(), "Description must not be blank");
    checkArgument(!valueDisplayName.isEmpty(), "Value Display Name must not be empty");
    checkArgument(name.startsWith("/"), "Name must be URL-like and start with a '/'");
    // TODO(b/30916431): strengthen metric name validation.

    return new AutoValue_MetricSchema(name, description, valueDisplayName, kind, labels);
  }

  public abstract String name();

  public abstract String description();

  public abstract String valueDisplayName();

  public abstract Kind kind();

  public abstract ImmutableSet<LabelDescriptor> labels();

  /**
   * The kind of metric. CUMULATIVE metrics have values relative to an initial value, and GAUGE
   * metrics have values which are standalone.
   */
  public enum Kind {
    CUMULATIVE,
    GAUGE,
  }
}
