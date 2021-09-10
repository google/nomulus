// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.toZonedDateTime;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.util.DateTimeUtils;
import java.time.ZonedDateTime;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Represents a registry lock/unlock object, meaning that the domain is locked on the registry
 * level.
 *
 * <p>Registry locks must be requested through the registrar console by a lock-enabled contact, then
 * confirmed through email within a certain length of time. Until that confirmation is processed,
 * the completion time will remain null and the lock will have no effect. The same applies for
 * unlock actions.
 *
 * <p>Note that there will be at most one row per domain with a null copmleted time -- this means
 * that there is at most one pending action per domain. This is enforced at the logic level.
 *
 * <p>Note as well that in the case of a retry of a write after an unexpected success, the unique
 * constraint on {@link #verificationCode} means that the second write will fail.
 */
@Entity
@Table(
    /**
     * Unique constraint to get around Hibernate's failure to handle auto-increment field in
     * composite primary key.
     *
     * <p>Note: indexes use the camelCase version of the field names because the {@link
     * google.registry.persistence.NomulusNamingStrategy} does not translate the field name into the
     * snake_case column name until the write itself.
     */
    indexes = {
      @Index(
          name = "idx_registry_lock_repo_id_revision_id",
          columnList = "repoId, revisionId",
          unique = true),
      @Index(name = "idx_registry_lock_verification_code", columnList = "verificationCode"),
      @Index(name = "idx_registry_lock_registrar_id", columnList = "registrarId")
    })
public final class RegistryLock extends ImmutableObject implements Buildable, SqlOnlyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  private Long revisionId;

  /** EPP repo ID of the domain in question. */
  @Column(nullable = false)
  private String repoId;

  // TODO (b/140568328): remove this when everything is in Cloud SQL and we can join on "domain"
  @Column(nullable = false)
  private String domainName;

  /**
   * The ID of the registrar that performed the action -- this may be the admin ID if this action
   * was performed by a superuser.
   */
  @Column(nullable = false)
  private String registrarId;

  /** The POC that performed the action, or null if it was a superuser. */
  private String registrarPocId;

  /** When the lock is first requested. */
  @Column(nullable = false)
  private CreateAutoTimestamp lockRequestTime = CreateAutoTimestamp.create(null);

  /** When the unlock is first requested. */
  private ZonedDateTime unlockRequestTime;

  /**
   * When the user has verified the lock. If this field is null, it means the lock has not been
   * verified yet (and thus not been put into effect).
   */
  private ZonedDateTime lockCompletionTime;

  /**
   * When the user has verified the unlock of this lock. If this field is null, it means the unlock
   * action has not been verified yet (and has not been put into effect).
   */
  private ZonedDateTime unlockCompletionTime;

  /** The user must provide the random verification code in order to complete the action. */
  @Column(nullable = false)
  private String verificationCode;

  /**
   * True iff this action was taken by a superuser, in response to something like a URS request. In
   * this case, the action was performed by a registry admin rather than a registrar.
   */
  @Column(nullable = false)
  private boolean isSuperuser;

  /** The lock that undoes this lock, if this lock has been unlocked and the domain locked again. */
  // TODO(b/176498743): Lazy loading on scalar field not supported by default. See bug for details.
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "relockRevisionId", referencedColumnName = "revisionId")
  private RegistryLock relock;

  /** The duration after which we will re-lock this domain after it is unlocked. */
  private Duration relockDuration;

  /** Time that this entity was last updated. */
  private UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  public String getRepoId() {
    return repoId;
  }

  public String getDomainName() {
    return domainName;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public String getRegistrarPocId() {
    return registrarPocId;
  }

  public DateTime getLockRequestTime() {
    return lockRequestTime.getTimestamp();
  }

  /** Returns the unlock request timestamp or null if an unlock has not been requested yet. */
  public Optional<DateTime> getUnlockRequestTime() {
    return Optional.ofNullable(unlockRequestTime).map(DateTimeUtils::toJodaDateTime);
  }

  /** Returns the completion timestamp, or empty if this lock has not been completed yet. */
  public Optional<DateTime> getLockCompletionTime() {
    return Optional.ofNullable(lockCompletionTime).map(DateTimeUtils::toJodaDateTime);
  }

  /**
   * Returns the unlock completion timestamp, or empty if this unlock has not been completed yet.
   */
  public Optional<DateTime> getUnlockCompletionTime() {
    return Optional.ofNullable(unlockCompletionTime).map(DateTimeUtils::toJodaDateTime);
  }

  public String getVerificationCode() {
    return verificationCode;
  }

  public boolean isSuperuser() {
    return isSuperuser;
  }

  public DateTime getLastUpdateTime() {
    return lastUpdateTime.getTimestamp();
  }

  public Long getRevisionId() {
    return revisionId;
  }

  /**
   * The lock that undoes this lock, if this lock has been unlocked and the domain locked again.
   *
   * <p>Note: this is lazily loaded, so it may not be initialized if referenced outside of the
   * transaction in which this lock is loaded.
   */
  public RegistryLock getRelock() {
    return relock;
  }

  /** The duration after which we will re-lock this domain after it is unlocked. */
  public Optional<Duration> getRelockDuration() {
    return Optional.ofNullable(relockDuration);
  }

  public boolean isLocked() {
    return lockCompletionTime != null && unlockCompletionTime == null;
  }

  /** Returns true iff the lock was requested &gt;= 1 hour ago and has not been verified. */
  public boolean isLockRequestExpired(DateTime now) {
    return !getLockCompletionTime().isPresent()
        && isBeforeOrAt(getLockRequestTime(), now.minusHours(1));
  }

  /** Returns true iff the unlock was requested &gt;= 1 hour ago and has not been verified. */
  public boolean isUnlockRequestExpired(DateTime now) {
    Optional<DateTime> unlockRequestTimestamp = getUnlockRequestTime();
    return unlockRequestTimestamp.isPresent()
        && !getUnlockCompletionTime().isPresent()
        && isBeforeOrAt(unlockRequestTimestamp.get(), now.minusHours(1));
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for {@link RegistryLock}. */
  public static class Builder extends Buildable.Builder<RegistryLock> {
    public Builder() {}

    private Builder(RegistryLock instance) {
      super(instance);
    }

    @Override
    public RegistryLock build() {
      checkArgumentNotNull(getInstance().repoId, "Repo ID cannot be null");
      checkArgumentNotNull(getInstance().domainName, "Domain name cannot be null");
      checkArgumentNotNull(getInstance().registrarId, "Registrar ID cannot be null");
      checkArgumentNotNull(getInstance().verificationCode, "Verification code cannot be null");
      checkArgument(
          getInstance().registrarPocId != null || getInstance().isSuperuser,
          "Registrar POC ID must be provided if superuser is false");
      return super.build();
    }

    public Builder setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return this;
    }

    public Builder setDomainName(String domainName) {
      getInstance().domainName = domainName;
      return this;
    }

    public Builder setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setRegistrarPocId(String registrarPocId) {
      getInstance().registrarPocId = registrarPocId;
      return this;
    }

    public Builder setUnlockRequestTime(DateTime unlockRequestTime) {
      getInstance().unlockRequestTime = toZonedDateTime(unlockRequestTime);
      return this;
    }

    public Builder setLockCompletionTime(DateTime lockCompletionTime) {
      getInstance().lockCompletionTime = toZonedDateTime(lockCompletionTime);
      return this;
    }

    public Builder setUnlockCompletionTime(DateTime unlockCompletionTime) {
      getInstance().unlockCompletionTime = toZonedDateTime(unlockCompletionTime);
      return this;
    }

    public Builder setVerificationCode(String verificationCode) {
      getInstance().verificationCode = verificationCode;
      return this;
    }

    public Builder isSuperuser(boolean isSuperuser) {
      getInstance().isSuperuser = isSuperuser;
      return this;
    }

    public Builder setRelock(RegistryLock relock) {
      getInstance().relock = relock;
      return this;
    }

    public Builder setRelockDuration(@Nullable Duration relockDuration) {
      getInstance().relockDuration = relockDuration;
      return this;
    }
  }
}
