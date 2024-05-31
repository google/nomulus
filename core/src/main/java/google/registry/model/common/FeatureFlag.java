// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.api.client.util.Preconditions.checkState;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;

@Entity
public class FeatureFlag extends ImmutableObject implements Buildable {

  /**
   * The current status of the feature the flag represents.
   *
   * <p>Currently, there is no enforced ordering of these status values, but that may change in the
   * future should new statuses be added to this enum that require it.
   */
  public enum FeatureStatus {
    ACTIVE,
    INACTIVE
  }

  /** The name of the flag/feature. */
  @Id String featureName;

  /** A map of times for each {@link FeatureStatus} the FeatureFlag should hold. */
  @Column(nullable = false)
  TimedTransitionProperty<FeatureStatus> status =
      TimedTransitionProperty.withInitialValue(FeatureStatus.INACTIVE);

  public String getFeatureName() {
    return featureName;
  }

  public TimedTransitionProperty<FeatureStatus> getStatusMap() {
    return status;
  }

  public FeatureStatus getStatus(DateTime time) {
    return status.getValueAtTime(time);
  }

  @Override
  public FeatureFlag.Builder asBuilder() {
    return new FeatureFlag.Builder(clone(this));
  }

  /** A builder for constructing {@link FeatureFlag} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<FeatureFlag> {

    public Builder() {}

    private Builder(FeatureFlag instance) {
      super(instance);
    }

    @Override
    public FeatureFlag build() {
      checkArgument(
          !Strings.isNullOrEmpty(getInstance().featureName),
          "Feature name must not be null or empty");
      getInstance().status.checkValidity();
      return super.build();
    }

    public Builder setFeatureName(String featureName) {
      checkState(getInstance().featureName == null, "Feature name can only be set once");
      checkArgumentNotNull(featureName, "Feature name must not be null");
      checkArgument(!featureName.isEmpty(), "Feature name must not be empty");
      getInstance().featureName = featureName;
      return this;
    }

    public Builder setStatus(ImmutableSortedMap<DateTime, FeatureStatus> statusMap) {
      getInstance().status = TimedTransitionProperty.fromValueMap(statusMap);
      return this;
    }
  }
}
