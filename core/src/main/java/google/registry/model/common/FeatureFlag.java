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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.Buildable;
import google.registry.model.CacheUtils;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyFeatureStatusDeserializer;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  @JsonDeserialize(using = TimedTransitionPropertyFeatureStatusDeserializer.class)
  TimedTransitionProperty<FeatureStatus> status =
      TimedTransitionProperty.withInitialValue(FeatureStatus.INACTIVE);

  public static Optional<FeatureFlag> getUncached(String featureName) {
    return tm().transact(() -> tm().loadByKeyIfPresent(createVKey(featureName)));
  }

  public static ImmutableList<FeatureFlag> getAllUncached() {
    return tm().transact(() -> tm().loadAllOf(FeatureFlag.class));
  }

  public static FeatureFlag get(String featureName) {
    Optional<FeatureFlag> maybeFeatureFlag = CACHE.get(featureName);
    if (maybeFeatureFlag.isEmpty()) {
      throw new FeatureFlagNotFoundException(featureName);
    } else {
      return maybeFeatureFlag.get();
    }
  }

  public static ImmutableSet<FeatureFlag> getAll(Set<String> featureNames) {
    Map<String, Optional<FeatureFlag>> featureFlags = CACHE.getAll(featureNames);
    ImmutableSet<String> missingFlags =
        featureFlags.entrySet().stream()
            .filter(e -> e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(toImmutableSet());
    if (missingFlags.isEmpty()) {
      return featureFlags.values().stream().map(Optional::get).collect(toImmutableSet());
    } else {
      throw new FeatureFlagNotFoundException(missingFlags);
    }
  }

  /** A cache that loads the {@link FeatureFlag} for a given featureName. */
  private static final LoadingCache<String, Optional<FeatureFlag>> CACHE =
      CacheUtils.newCacheBuilder(getSingletonCacheRefreshDuration())
          .build(
              new CacheLoader<>() {
                @Override
                public Optional<FeatureFlag> load(final String featureName) {
                  return tm().transact(() -> tm().loadByKeyIfPresent(createVKey(featureName)));
                }

                @Override
                public Map<? extends String, ? extends Optional<FeatureFlag>> loadAll(
                    Set<? extends String> featureFlagNames) {
                  ImmutableMap<String, VKey<FeatureFlag>> keysMap =
                      featureFlagNames.stream()
                          .collect(
                              toImmutableMap(featureName -> featureName, FeatureFlag::createVKey));
                  return tm().transact(
                          () ->
                              featureFlagNames.stream()
                                  .collect(
                                      toImmutableMap(
                                          flag -> flag,
                                          flag -> tm().loadByKeyIfPresent(keysMap.get(flag)))));
                }
              });

  public static VKey<FeatureFlag> createVKey(String featureName) {
    return VKey.create(FeatureFlag.class, featureName);
  }

  @Override
  public VKey<FeatureFlag> createVKey() {
    return createVKey(featureName);
  }

  public String getFeatureName() {
    return featureName;
  }

  @JsonProperty("status")
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
      checkState(
          getInstance().featureName == null || getInstance().featureName.equals(featureName),
          "Feature name can only be set once");
      getInstance().featureName = featureName;
      return this;
    }

    public Builder setStatusMap(ImmutableSortedMap<DateTime, FeatureStatus> statusMap) {
      getInstance().status = TimedTransitionProperty.fromValueMap(statusMap);
      return this;
    }
  }

  /** Exception to throw when no FeatureFlag entity is found for given FeatureName string(s). */
  public static class FeatureFlagNotFoundException extends RuntimeException {

    FeatureFlagNotFoundException(ImmutableSet<String> featureNames) {
      super("No feature flag object(s) found for " + Joiner.on(", ").join(featureNames));
    }

    public FeatureFlagNotFoundException(String featureName) {
      this(ImmutableSet.of(featureName));
    }
  }
}
