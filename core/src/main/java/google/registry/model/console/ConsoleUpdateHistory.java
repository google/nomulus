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

package google.registry.model.console;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.IdAllocation;
import google.registry.persistence.WithVKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Optional;
import org.joda.time.DateTime;

@Entity
@WithVKey(Long.class)
@Table(
    indexes = {
      @Index(columnList = "actingUser", name = "idx_console_update_history_acting_user"),
      @Index(columnList = "type", name = "idx_console_update_history_type"),
      @Index(columnList = "modificationTime", name = "idx_console_update_history_modification_time")
    })
public class ConsoleUpdateHistory extends ImmutableObject implements Buildable {

  @Id @IdAllocation @Column Long revisionId;

  @Column(nullable = false)
  DateTime modificationTime;

  /** The HTTP method (e.g. POST, PUT) used to make this modification. */
  @Column(nullable = false)
  String method;

  /** The type of modification. */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Type type;

  /** The URL of the action that was used to make the modification. */
  @Column(nullable = false)
  String url;

  /** An optional further description of the action. */
  String description;

  /** The user that performed the modification. */
  @JoinColumn(name = "actingUser", referencedColumnName = "emailAddress", nullable = false)
  @ManyToOne
  User actingUser;

  public Long getRevisionId() {
    return revisionId;
  }

  public DateTime getModificationTime() {
    return modificationTime;
  }

  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }

  public String getMethod() {
    return method;
  }

  public Type getType() {
    return type;
  }

  public String getUrl() {
    return url;
  }

  public User getActingUser() {
    return actingUser;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public enum Type {
    DOMAIN_DELETE,
    DOMAIN_SUSPEND,
    DOMAIN_UNSUSPEND,
    EPP_PASSWORD_UPDATE,
    REGISTRAR_CREATE,
    REGISTRAR_SECURITY_UPDATE,
    REGISTRAR_UPDATE,
    USER_CREATE,
    USER_DELETE,
    USER_UPDATE
  }

  public static class Builder extends Buildable.Builder<ConsoleUpdateHistory> {
    public Builder() {}

    private Builder(ConsoleUpdateHistory instance) {
      super(instance);
    }

    @Override
    public ConsoleUpdateHistory build() {
      checkArgumentNotNull(getInstance().modificationTime, "Modification time must be specified");
      checkArgumentNotNull(getInstance().actingUser, "Acting user must be specified");
      checkArgumentNotNull(getInstance().url, "URL must be specified");
      checkArgumentNotNull(getInstance().method, "HTTP method must be specified");
      checkArgumentNotNull(getInstance().type, "ConsoleUpdateHistory type must be specified");
      return super.build();
    }

    public Builder setModificationTime(DateTime modificationTime) {
      getInstance().modificationTime = modificationTime;
      return this;
    }

    public Builder setActingUser(User actingUser) {
      getInstance().actingUser = actingUser;
      return this;
    }

    public Builder setUrl(String url) {
      getInstance().url = url;
      return this;
    }

    public Builder setMethod(String method) {
      getInstance().method = method;
      return this;
    }

    public Builder setDescription(String description) {
      getInstance().description = description;
      return this;
    }

    public Builder setType(Type type) {
      getInstance().type = type;
      return this;
    }
  }
}
