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

package google.registry.schema.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.DateTimeUtils.toJodaDateTime;
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
    // Unique constraint to get around Hibernate's failure to handle
    // auto-increment field in composite primary key.
    indexes =
        @Index(
            name = "idx_registry_lock_repo_id_revision_id",
            columnList = "repo_id, revision_id",
            unique = true))
public final class RegistryLock extends ImmutableObject implements Buildable {

  /** Describes the action taken by the user. */
  public enum Action {
    LOCK,
    UNLOCK
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "revision_id", nullable = false)
  private Long revisionId;

  /** EPP repo ID of the domain in question. */
  @Column(name = "repo_id", nullable = false)
  private String repoId;

  // TODO (b/140568328): remove this when everything is in Cloud SQL and we can join on "domain"
  @Column(name = "domain_name", nullable = false)
  private String domainName;

  /**
   * The ID of the registrar that performed the action -- this may be the admin ID if this action
   * was performed by a superuser.
   */
  @Column(name = "registrar_id", nullable = false)
  private String registrarId;

  /** The POC that performed the action, or null if it was a superuser. */
  @Column(name = "registrar_poc_id")
  private String registrarPocId;

  /**
   * Lock action is immutable and describes whether the action performed was a lock or an unlock.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false)
  private Action action;

  /** Creation timestamp is when the lock/unlock is first requested. */
  @Column(name = "creation_timestamp", nullable = false)
  private ZonedDateTime creationTimestamp;

  /**
   * Completion timestamp is when the user has verified the lock/unlock, when this object de facto
   * becomes immutable. If this field is null, it means that the lock has not been verified yet (and
   * thus not been put into effect).
   */
  @Column(name = "completion_timestamp")
  private ZonedDateTime completionTimestamp;

  /**
   * The user must provide the random verification code in order to complete the lock and move the
   * status from PENDING to COMPLETED.
   */
  @Column(name = "verification_code", nullable = false)
  private String verificationCode;

  /**
   * True iff this action was taken by a superuser, in response to something like a URS request. In
   * this case, the action was performed by a registry admin rather than a registrar.
   */
  @Column(name = "is_superuser", nullable = false)
  private boolean isSuperuser;

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

  public Action getAction() {
    return action;
  }

  public DateTime getCreationTimestamp() {
    return toJodaDateTime(creationTimestamp);
  }

  public DateTime getCompletionTimestamp() {
    return toJodaDateTime(completionTimestamp);
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

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for {@link google.registry.schema.domain.RegistryLock}. */
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
      checkArgumentNotNull(getInstance().action, "Action cannot be null");
      checkArgumentNotNull(getInstance().creationTimestamp, "Creation timestamp cannot be null");
      checkArgumentNotNull(getInstance().verificationCode, "Verification codecannot be null");
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

    public Builder setAction(Action action) {
      getInstance().action = action;
      return this;
    }

    public Builder setCreationTimestamp(DateTime creationTimestamp) {
      getInstance().creationTimestamp = toZonedDateTime(creationTimestamp);
      return this;
    }

    public Builder setCompletionTimestamp(DateTime lockTimestamp) {
      getInstance().completionTimestamp = toZonedDateTime(lockTimestamp);
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
  }
}
