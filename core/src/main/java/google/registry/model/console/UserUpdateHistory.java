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

import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;

/**
 * A persisted history object representing an update to a User.
 *
 * <p>In addition to the generic history fields (time, URL, etc.) we also persist a copy of the
 * modified User object at this point in time.
 */
@Access(AccessType.FIELD)
@Entity
@Table(indexes = {@Index(columnList = "historyActingUser"), @Index(columnList = "emailAddress")})
public class UserUpdateHistory extends ConsoleUpdateHistory {

  UserBase user;

  @Column(nullable = false, name = "emailAddress")
  String emailAddress;

  public UserBase getUser() {
    return user;
  }

  @PostLoad
  void postLoad() {
    user.setEmailAddress(emailAddress);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<UserUpdateHistory> createVKey() {
    return VKey.create(UserUpdateHistory.class, getRevisionId());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for the immutable UserUpdateHistory. */
  public static class Builder extends ConsoleUpdateHistory.Builder<UserUpdateHistory, Builder> {

    public Builder() {}

    public Builder(UserUpdateHistory instance) {
      super(instance);
    }

    @Override
    public UserUpdateHistory build() {
      checkArgumentNotNull(getInstance().user, "User must be specified");
      return super.build();
    }

    public Builder setUser(User user) {
      getInstance().user = user;
      getInstance().emailAddress = user.getEmailAddress();
      return this;
    }
  }
}
