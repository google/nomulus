// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import google.registry.persistence.VKey;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

/** A console user, either a registry employee or a registrar partner. */
@Embeddable
@Entity
@Table(indexes = {@Index(columnList = "emailAddress", name = "user_email_address_idx")})
public class User extends UserBase {

  @Override
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Access(AccessType.PROPERTY)
  public Long getId() {
    return super.getId();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public VKey<User> createVKey() {
    return VKey.create(User.class, getId());
  }

  /** Builder for constructing immutable {@link User} objects. */
  public static class Builder extends UserBase.Builder<User, Builder> {

    public Builder() {}

    public Builder(User user) {
      super(user);
    }
  }
}
