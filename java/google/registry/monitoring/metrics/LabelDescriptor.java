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
import com.google.re2j.Pattern;

/**
 * Definition of a metric label.
 *
 * <p>If a metric is created with labels, corresponding label values must be provided when setting
 * values on the metric.
 */
@AutoValue
public abstract class LabelDescriptor {

  private static final Pattern ALLOWED_LABEL_PATTERN = Pattern.compile("\\w+");

  LabelDescriptor() {}

  /**
   * Returns a new {@link LabelDescriptor}.
   *
   * @param name identifier for label
   * @param description human-readable description of label
   * @throws IllegalArgumentException if {@code name} isn't {@code \w+} or {@code description} is
   *     blank
   */
  public static LabelDescriptor create(String name, String description) {
    checkArgument(!name.isEmpty(), "Name must not be empty");
    checkArgument(!description.isEmpty(), "Description must not be empty");
    checkArgument(
        ALLOWED_LABEL_PATTERN.matches(name),
        "Label name must match the regex %s",
        ALLOWED_LABEL_PATTERN);

    return new AutoValue_LabelDescriptor(name, description);
  }

  public abstract String name();

  public abstract String description();
}
