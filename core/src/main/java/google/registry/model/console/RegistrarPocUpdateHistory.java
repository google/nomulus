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

import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPocBase;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;

/**
 * A persisted history object representing an update to a RegistrarPoc.
 *
 * <p>In addition to the generic history fields (time, URL, etc.) we also persist a copy of the
 * modified RegistrarPoc object at this point in time.
 */
@Access(AccessType.FIELD)
@Entity
@Table(
    indexes = {
      @Index(columnList = "historyActingUser"),
      @Index(columnList = "emailAddress"),
      @Index(columnList = "registrarId")
    })
public class RegistrarPocUpdateHistory extends ConsoleUpdateHistory {

  RegistrarPocBase registrarPoc;

  // These fields exist so that they can be populated in the SQL table
  @Column(nullable = false)
  String emailAddress;

  @Column(nullable = false)
  String registrarId;

  public RegistrarPocBase getRegistrarPoc() {
    return registrarPoc;
  }

  @PostLoad
  void postLoad() {
    registrarPoc.setEmailAddress(emailAddress);
    registrarPoc.setRegistrarId(registrarId);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<RegistrarPocUpdateHistory> createVKey() {
    return VKey.create(RegistrarPocUpdateHistory.class, getRevisionId());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for the immutable UserUpdateHistory. */
  public static class Builder
      extends ConsoleUpdateHistory.Builder<RegistrarPocUpdateHistory, Builder> {

    public Builder() {}

    public Builder(RegistrarPocUpdateHistory instance) {
      super(instance);
    }

    @Override
    public RegistrarPocUpdateHistory build() {
      checkArgumentNotNull(getInstance().registrarPoc, "Registrar POC must be specified");
      return super.build();
    }

    public Builder setRegistrarPoc(RegistrarPoc registrarPoc) {
      getInstance().registrarPoc = registrarPoc;
      getInstance().registrarId = registrarPoc.getRegistrarId();
      getInstance().emailAddress = registrarPoc.getEmailAddress();
      return this;
    }
  }
}
