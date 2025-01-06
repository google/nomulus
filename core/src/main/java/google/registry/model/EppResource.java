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

package google.registry.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.annotations.Expose;
import google.registry.config.RegistryConfig;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.transfer.TransferData;
import google.registry.persistence.VKey;
import google.registry.util.NonFinalForTesting;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/** An EPP entity object (i.e. a domain, contact, or host). */
@MappedSuperclass
@Access(AccessType.FIELD) // otherwise it'll use the default of the repoId (property)
public abstract class EppResource extends UpdateAutoTimestampEntity implements Buildable {

  private static final long serialVersionUID = -252782773382339534L;

  /**
   * Unique identifier in the registry for this resource.
   *
   * <p>Not persisted so that we can store these in references to other objects. Subclasses that
   * wish to use this as the primary key should create a getter method annotated with @Id
   *
   * <p>This is in the (\w|_){1,80}-\w{1,8} format specified by RFC 5730 for roidType.
   *
   * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 5730</a>
   */
  @Expose @Transient String repoId;

  /**
   * The ID of the registrar that is currently sponsoring this resource.
   *
   * <p>This can be null in the case of pre-Registry-3.0-migration history objects with null
   * resource fields.
   */
  @Expose String currentSponsorRegistrarId;

  /**
   * The ID of the registrar that created this resource.
   *
   * <p>This can be null in the case of pre-Registry-3.0-migration history objects with null
   * resource fields.
   */
  @Expose String creationRegistrarId;

  /**
   * The ID of the registrar that last updated this resource.
   *
   * <p>This does not refer to the last delta made on this object, which might include out-of-band
   * edits; it only includes EPP-visible modifications such as {@literal <update>}. Can be null if
   * the resource has never been modified.
   */
  @Expose String lastEppUpdateRegistrarId;

  /**
   * The time when this resource was created.
   *
   * <p>Map the method to XML, not the field, because if we map the field (with an adaptor class) it
   * will never be omitted from the xml even if the timestamp inside creationTime is null, and we
   * return null from the adaptor (instead it gets written as an empty tag).
   *
   * <p>This can be null in the case of pre-Registry-3.0-migration history objects with null
   * resource fields.
   */
  // Need to override the default non-null column attribute.
  @AttributeOverride(name = "creationTime", column = @Column)
  @Expose
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /**
   * The time when this resource was or will be deleted.
   *
   * <ul>
   *   <li>For deleted resources, this is in the past.
   *   <li>For pending-delete resources, this is in the near future.
   *   <li>For active resources, this is {@code END_OF_TIME}.
   * </ul>
   *
   * <p>This scheme allows for setting pending deletes in the future and having them magically drop
   * out of the index at that time, as long as we query for resources whose deletion time is before
   * now.
   */
  DateTime deletionTime;

  /**
   * The time that this resource was last updated.
   *
   * <p>This does not refer to the last delta made on this object, which might include out-of-band
   * edits; it only includes EPP-visible modifications such as {@literal <update>}. Can be null if
   * the resource has never been modified.
   */
  @Expose DateTime lastEppUpdateTime;

  /** Status values associated with this resource. */
  @Enumerated(EnumType.STRING)
  @Expose
  Set<StatusValue> statuses;

  public String getRepoId() {
    return repoId;
  }

  /**
   * Sets the repository ID.
   *
   * <p>This should only be used for restoring the repo id of an object being loaded in a PostLoad
   * method (effectively, when it is still under construction by Hibernate). In all other cases, the
   * object should be regarded as immutable and changes should go through a Builder.
   *
   * <p>In addition to this special case use, this method must exist to satisfy Hibernate.
   */
  public void setRepoId(String repoId) {
    this.repoId = repoId;
  }

  public final DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public String getCreationRegistrarId() {
    return creationRegistrarId;
  }

  public DateTime getLastEppUpdateTime() {
    return lastEppUpdateTime;
  }

  public String getLastEppUpdateRegistrarId() {
    return lastEppUpdateRegistrarId;
  }

  /**
   * Get the stored value of {@link #currentSponsorRegistrarId}.
   *
   * <p>For subordinate hosts, this value may not represent the actual current client id, which is
   * the client id of the superordinate host. For all other resources this is the true client id.
   */
  public final String getPersistedCurrentSponsorRegistrarId() {
    return currentSponsorRegistrarId;
  }

  public final ImmutableSet<StatusValue> getStatusValues() {
    return nullToEmptyImmutableCopy(statuses);
  }

  public DateTime getDeletionTime() {
    return deletionTime;
  }

  /** Return a clone of the resource with timed status values modified using the given time. */
  public abstract EppResource cloneProjectedAtTime(DateTime now);

  /** Get the foreign key string for this resource. */
  public abstract String getForeignKey();

  /** Create the VKey for the specified EPP resource. */
  @Override
  public abstract VKey<? extends EppResource> createVKey();

  /** Override of {@link Buildable#asBuilder} so that the extra methods are visible. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** EppResources that are loaded via foreign keys should implement this marker interface. */
  public interface ForeignKeyedEppResource {}

  /** An interface for resources that have transfer data. */
  public interface ResourceWithTransferData<T extends TransferData> {
    T getTransferData();

    /**
     * The time that this resource was last transferred.
     *
     * <p>Can be null if the resource has never been transferred.
     */
    DateTime getLastTransferTime();
  }

  /** An interface for builders of resources that have transfer data. */
  public interface BuilderWithTransferData<
      T extends TransferData, B extends BuilderWithTransferData<T, B>> {
    B setTransferData(T transferData);

    /** Set the time when this resource was transferred. */
    B setLastTransferTime(DateTime lastTransferTime);
  }

  /** Abstract builder for {@link EppResource} types. */
  public abstract static class Builder<T extends EppResource, B extends Builder<T, B>>
      extends GenericBuilder<T, B> {

    /** Create a {@link Builder} wrapping a new instance. */
    protected Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    protected Builder(T instance) {
      super(instance);
    }

    /**
     * Set the time this resource was created.
     *
     * <p>Note: This can only be used if the creation time hasn't already been set, which it is in
     * normal EPP flows.
     */
    public B setCreationTime(DateTime creationTime) {
      checkState(
          getInstance().creationTime.getTimestamp() == null,
          "creationTime can only be set once for EppResource.");
      getInstance().creationTime = CreateAutoTimestamp.create(creationTime);
      return thisCastToDerived();
    }

    /** Set the time this resource was created. Should only be used in tests. */
    @VisibleForTesting
    public B setCreationTimeForTest(DateTime creationTime) {
      getInstance().creationTime = CreateAutoTimestamp.create(creationTime);
      return thisCastToDerived();
    }

    /** Set the time after which this resource should be considered deleted. */
    public B setDeletionTime(DateTime deletionTime) {
      getInstance().deletionTime = deletionTime;
      return thisCastToDerived();
    }

    /** Set the current sponsoring registrar. */
    public B setPersistedCurrentSponsorRegistrarId(String currentSponsorRegistrarId) {
      getInstance().currentSponsorRegistrarId = currentSponsorRegistrarId;
      return thisCastToDerived();
    }

    /** Set the registrar that created this resource. */
    public B setCreationRegistrarId(String creationRegistrarId) {
      getInstance().creationRegistrarId = creationRegistrarId;
      return thisCastToDerived();
    }

    /** Set the time when a {@literal <update>} was performed on this resource. */
    public B setLastEppUpdateTime(DateTime lastEppUpdateTime) {
      getInstance().lastEppUpdateTime = lastEppUpdateTime;
      return thisCastToDerived();
    }

    /** Set the registrar who last performed a {@literal <update>} on this resource. */
    public B setLastEppUpdateRegistrarId(String lastEppUpdateRegistrarId) {
      getInstance().lastEppUpdateRegistrarId = lastEppUpdateRegistrarId;
      return thisCastToDerived();
    }

    /** Set this resource's status values. */
    public B setStatusValues(ImmutableSet<StatusValue> statusValues) {
      Class<? extends EppResource> resourceClass = getInstance().getClass();
      for (StatusValue statusValue : nullToEmpty(statusValues)) {
        checkArgument(
            statusValue.isAllowedOn(resourceClass),
            "The %s status cannot be set on %s",
            statusValue,
            resourceClass.getSimpleName());
      }
      getInstance().statuses = statusValues;
      return thisCastToDerived();
    }

    /** Add to this resource's status values. */
    public B addStatusValue(StatusValue statusValue) {
      return addStatusValues(ImmutableSet.of(statusValue));
    }

    /** Remove from this resource's status values. */
    public B removeStatusValue(StatusValue statusValue) {
      return removeStatusValues(ImmutableSet.of(statusValue));
    }

    /** Add to this resource's status values. */
    public B addStatusValues(ImmutableSet<StatusValue> statusValues) {
      return setStatusValues(
          ImmutableSet.copyOf(union(getInstance().getStatusValues(), statusValues)));
    }

    /** Remove from this resource's status values. */
    public B removeStatusValues(ImmutableSet<StatusValue> statusValues) {
      return setStatusValues(
          ImmutableSet.copyOf(difference(getInstance().getStatusValues(), statusValues)));
    }

    /** Set this resource's repoId. */
    public B setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return thisCastToDerived();
    }

    /**
     * Set the update timestamp.
     *
     * <p>This is provided at EppResource since UpdateAutoTimestampEntity doesn't have a Builder.
     */
    public B setUpdateTimestamp(UpdateAutoTimestamp updateTimestamp) {
      getInstance().setUpdateTimestamp(updateTimestamp);
      return thisCastToDerived();
    }

    /** Build the resource, nullifying empty strings and sets and setting defaults. */
    @Override
    public T build() {
      // An EPP object has an implicit status of OK if no pending operations or prohibitions exist.
      removeStatusValue(StatusValue.OK);
      if (getInstance().getStatusValues().isEmpty()) {
        addStatusValue(StatusValue.OK);
      }
      // If there is no deletion time, set it to END_OF_TIME.
      setDeletionTime(Optional.ofNullable(getInstance().deletionTime).orElse(END_OF_TIME));
      return ImmutableObject.cloneEmptyToNull(super.build());
    }
  }

  static final CacheLoader<VKey<? extends EppResource>, EppResource> CACHE_LOADER =
      new CacheLoader<>() {

        @Override
        public EppResource load(VKey<? extends EppResource> key) {
          return replicaTm().reTransact(() -> replicaTm().loadByKey(key));
        }

        @Override
        public Map<? extends VKey<? extends EppResource>, ? extends EppResource> loadAll(
            Set<? extends VKey<? extends EppResource>> keys) {
          return replicaTm().reTransact(() -> replicaTm().loadByKeys(keys));
        }
      };

  /**
   * A limited size, limited time cache for EPP resource entities.
   *
   * <p>This is used to cache contacts and hosts for the purposes of checking whether they are
   * deleted or in pending delete during a few domain flows, and also to cache domains for the
   * purpose of determining restore fees in domain checks. Any mutating operations directly on EPP
   * resources should of course never use the cache as they always need perfectly up-to-date
   * information.
   */
  @NonFinalForTesting
  private static LoadingCache<VKey<? extends EppResource>, EppResource> cacheEppResources =
      createEppResourcesCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<? extends EppResource>, EppResource> createEppResourcesCache(
      Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    cacheEppResources = createEppResourcesCache(effectiveExpiry);
  }

  /**
   * Loads the given EppResources by their keys using the cache (if enabled).
   *
   * <p>Don't use this unless you really need it for performance reasons, and be sure that you are
   * OK with the trade-offs in loss of transactional consistency.
   */
  public static ImmutableMap<VKey<? extends EppResource>, EppResource> loadCached(
      Iterable<VKey<? extends EppResource>> keys) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return tm().reTransact(() -> tm().loadByKeys(keys));
    }
    return ImmutableMap.copyOf(cacheEppResources.getAll(keys));
  }

  /**
   * Loads a given EppResource by its key using the cache (if enabled).
   *
   * <p>Don't use this unless you really need it for performance reasons, and be sure that you are
   * OK with the trade-offs in loss of transactional consistency.
   */
  public static <T extends EppResource> T loadCached(VKey<T> key) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return tm().reTransact(() -> tm().loadByKey(key));
    }
    // Safe to cast because loading a Key<T> returns an entity of type T.
    @SuppressWarnings("unchecked")
    T resource = (T) cacheEppResources.get(key);
    return resource;
  }
}
