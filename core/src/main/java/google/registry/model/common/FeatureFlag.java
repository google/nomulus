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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import google.registry.model.Buildable;
import google.registry.model.CacheUtils;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyFeatureStatusDeserializer;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.FeatureStatusTransitionUserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.annotations.Type;
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

  /** The names of the feature flags that can be individually set. */
  public enum FeatureName {

    /** Feature flag name used for testing only. */
    TEST_FEATURE(FeatureStatus.INACTIVE),

    /** If we're not requiring the presence of contact data on domain EPP commands. */
    MINIMUM_DATASET_CONTACTS_OPTIONAL(FeatureStatus.INACTIVE),

    /** If we're not permitting the presence of contact data on any EPP commands. */
    MINIMUM_DATASET_CONTACTS_PROHIBITED(FeatureStatus.INACTIVE),

    /**
     * If we're including the upcoming domain drop date in the exported list of registered domains.
     */
    INCLUDE_PENDING_DELETE_DATE_FOR_DOMAINS(FeatureStatus.INACTIVE),

    /** If we're prohibiting the inclusion of the contact object URI on login. */
    PROHIBIT_CONTACT_OBJECTS_ON_LOGIN(FeatureStatus.INACTIVE);

    private final FeatureStatus defaultStatus;

    FeatureName(FeatureStatus defaultStatus) {
      this.defaultStatus = defaultStatus;
    }

    FeatureStatus getDefaultStatus() {
      return this.defaultStatus;
    }
  }

  /** The name of the flag/feature. */
  @Enumerated(EnumType.STRING)
  @Id
  FeatureName featureName;

  /** A map of times for each {@link FeatureStatus} the FeatureFlag should hold. */
  @Column(nullable = false)
  @Type(FeatureStatusTransitionUserType.class)
  @JsonDeserialize(using = TimedTransitionPropertyFeatureStatusDeserializer.class)
  TimedTransitionProperty<FeatureStatus> status =
      TimedTransitionProperty.withInitialValue(FeatureStatus.INACTIVE);

  public static Optional<FeatureFlag> getUncached(FeatureName featureName) {
    return tm().reTransact(() -> tm().loadByKeyIfPresent(createVKey(featureName)));
  }

  public static ImmutableList<FeatureFlag> getAllUncached() {
    return tm().transact(() -> tm().loadAllOf(FeatureFlag.class));
  }

  public static FeatureFlag get(FeatureName featureName) {
    Optional<FeatureFlag> maybeFeatureFlag = CACHE.get(featureName);
    return maybeFeatureFlag.orElseThrow(() -> new FeatureFlagNotFoundException(featureName));
  }

  public static ImmutableSet<FeatureFlag> getAll(Set<FeatureName> featureNames) {
    Map<FeatureName, Optional<FeatureFlag>> featureFlags = CACHE.getAll(featureNames);
    ImmutableSet<FeatureName> missingFlags =
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
  private static final LoadingCache<FeatureName, Optional<FeatureFlag>> CACHE =
      CacheUtils.newCacheBuilder(getSingletonCacheRefreshDuration())
          .build(
              new CacheLoader<>() {
                @Override
                public Optional<FeatureFlag> load(final FeatureName featureName) {
                  return tm().reTransact(() -> tm().loadByKeyIfPresent(createVKey(featureName)));
                }

                @Override
                public Map<? extends FeatureName, ? extends Optional<FeatureFlag>> loadAll(
                    Set<? extends FeatureName> featureFlagNames) {
                  ImmutableMap<FeatureName, VKey<FeatureFlag>> keysMap =
                      featureFlagNames.stream()
                          .collect(
                              toImmutableMap(featureName -> featureName, FeatureFlag::createVKey));
                  Map<VKey<? extends FeatureFlag>, FeatureFlag> entities =
                      tm().reTransact(() -> tm().loadByKeysIfPresent(keysMap.values()));
                  return Maps.toMap(
                      featureFlagNames,
                      name -> Optional.ofNullable(entities.get(createVKey(name))));
                }
              });

  public static VKey<FeatureFlag> createVKey(FeatureName featureName) {
    return VKey.create(FeatureFlag.class, featureName);
  }

  @Override
  public VKey<FeatureFlag> createVKey() {
    return createVKey(featureName);
  }

  public FeatureName getFeatureName() {
    return featureName;
  }

  @JsonProperty("status")
  public TimedTransitionProperty<FeatureStatus> getStatusMap() {
    return status;
  }

  public FeatureStatus getStatus(DateTime time) {
    return status.getValueAtTime(time);
  }

  /**
   * Returns whether the flag is active now, or else the flag's default value if it doesn't exist.
   */
  public static boolean isActiveNow(FeatureName featureName) {
    tm().assertInTransaction();
    return isActiveAt(featureName, tm().getTransactionTime());
  }

  /**
   * Returns whether the flag is active at the given time, or else the flag's default value if it
   * doesn't exist.
   */
  public static boolean isActiveAt(FeatureName featureName, DateTime dateTime) {
    tm().assertInTransaction();
    return CACHE
        .get(featureName)
        .map(flag -> flag.getStatus(dateTime).equals(ACTIVE))
        .orElse(featureName.getDefaultStatus().equals(ACTIVE));
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
      getInstance().status.checkValidity();
      checkArgument(getInstance().featureName != null, "FeatureName cannot be null");
      return super.build();
    }

    public Builder setFeatureName(FeatureName featureName) {
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

    FeatureFlagNotFoundException(ImmutableSet<FeatureName> featureNames) {
      super("No feature flag object(s) found for " + Joiner.on(", ").join(featureNames));
    }

    public FeatureFlagNotFoundException(FeatureName featureName) {
      this(ImmutableSet.of(featureName));
    }
  }
}
