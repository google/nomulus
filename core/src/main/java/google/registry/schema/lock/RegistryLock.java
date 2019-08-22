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

package google.registry.schema.lock;

import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toZonedDateTime;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import java.time.ZonedDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

/**
 * Represents a registry lock/unlock object, meaning that the domain is locked on the registry
 * level.
 *
 * <p>Registry locks must be requested through the registrar console by a lock-enabled contact, then
 * confirmed through email within a certain length of time. Until that confirmation is processed,
 * the lock will remain in PENDING status and will have no effect. The same applies for unlock
 * actions.
 *
 * <p>Note that in the case of a retry of a write after an unexpected success, the unique constraint
 * on {@link #verificationCode} means that the second write will fail.
 */
@Entity
@Table(
    // Unique constraint to get around Hibernate's failure to handle
    // auto-increment field in composite primary key.
    indexes =
        @Index(
            name = "idx_registry_lock_repo_id_revision_id",
            columnList = "repo_id, revision_id",
            unique = true))
public final class RegistryLock extends ImmutableObject implements Buildable {

  /** Describes the action taken by the user. */
  public enum LockAction {
    LOCK,
    UNLOCK
  }

  /** Describes if a domain is not locked, locked, or if a lock has been requested. */
  public enum LockStatus {
    NOT_LOCKED,
    PENDING,
    LOCKED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "revision_id", nullable = false)
  private Long revisionId;

  @Column(name = "repo_id", nullable = false)
  private String repoId;

  @Column(name = "domain_name", nullable = false)
  private String domainName;

  @Column(name = "registrar_client_id", nullable = false)
  private String registrarClientId;

  @Column(name = "registrar_contact_id", nullable = false)
  private String registrarContactId;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_action", nullable = false)
  private LockAction lockAction;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_status", nullable = false)
  private LockStatus lockStatus;

  @Column(name = "creation_timestamp", nullable = false)
  private ZonedDateTime creationTimestamp;

  @Column(name = "lock_timestamp")
  private ZonedDateTime lockTimestamp;

  @Column(name = "unlock_timestamp")
  private ZonedDateTime unlockTimestamp;

  @Column(name = "verification_code", nullable = false, unique = true)
  private String verificationCode;

  @Column(name = "is_superuser", nullable = false)
  private boolean isSuperuser;

  public String getRepoId() {
    return repoId;
  }

  public String getDomainName() {
    return domainName;
  }

  public String getRegistrarClientId() {
    return registrarClientId;
  }

  public String getRegistrarContactId() {
    return registrarContactId;
  }

  public LockAction getLockAction() {
    return lockAction;
  }

  public DateTime getCreationTimestamp() {
    return toDateTime(creationTimestamp);
  }

  public DateTime getLockTimestamp() {
    return toDateTime(lockTimestamp);
  }

  public DateTime getUnlockTimestamp() {
    return toDateTime(unlockTimestamp);
  }

  public String getVerificationCode() {
    return verificationCode;
  }

  public boolean isSuperuser() {
    return isSuperuser;
  }

  public Long getRevisionId() {
    return revisionId;
  }

  public LockStatus getLockStatus() {
    return lockStatus;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for {@link google.registry.schema.lock.RegistryLock}. */
  public static class Builder extends Buildable.Builder<RegistryLock> {
    public Builder() {}

    private Builder(RegistryLock instance) {
      super(instance);
    }

    @Override
    public RegistryLock build() {
      checkArgumentNotNull(getInstance().repoId, "Repo ID cannot be null");
      checkArgumentNotNull(getInstance().domainName, "Domain name cannot be null");
      checkArgumentNotNull(getInstance().registrarClientId, "Registrar client ID cannot be null");
      checkArgumentNotNull(getInstance().registrarContactId, "Registrar contact ID cannot be null");
      checkArgumentNotNull(getInstance().lockAction, "Lock action cannot be null");
      checkArgumentNotNull(getInstance().lockStatus, "Lock status cannot be null");
      checkArgumentNotNull(getInstance().creationTimestamp, "Creation timestamp cannot be null");
      checkArgumentNotNull(getInstance().verificationCode, "Verification codecannot be null");
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

    public Builder setRegistrarClientId(String registrarClientId) {
      getInstance().registrarClientId = registrarClientId;
      return this;
    }

    public Builder setRegistrarContactId(String registrarContactId) {
      getInstance().registrarContactId = registrarContactId;
      return this;
    }

    public Builder setLockAction(LockAction lockAction) {
      getInstance().lockAction = lockAction;
      return this;
    }

    public Builder setCreationTimestamp(DateTime creationTimestamp) {
      getInstance().creationTimestamp = toZonedDateTime(creationTimestamp);
      return this;
    }

    public Builder setLockTimestamp(DateTime lockTimestamp) {
      getInstance().lockTimestamp = toZonedDateTime(lockTimestamp);
      return this;
    }

    public Builder setUnlockTimestamp(DateTime unlockTimestamp) {
      getInstance().unlockTimestamp = toZonedDateTime(unlockTimestamp);
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

    public Builder setLockStatus(LockStatus lockStatus) {
      getInstance().lockStatus = lockStatus;
      return this;
    }
  }
}
