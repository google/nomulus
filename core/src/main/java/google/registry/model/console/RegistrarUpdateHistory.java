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

import google.registry.model.registrar.RegistrarBase;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;

/**
 * A persisted history object representing an update to a Registrar.
 *
 * <p>In addition to the generic history fields (time, URL, etc.) we also persist a copy of the
 * modified Registrar object at this point in time.
 */
@Access(AccessType.FIELD)
@Entity
@Table(indexes = {@Index(columnList = "historyActingUser"), @Index(columnList = "registrarId")})
public class RegistrarUpdateHistory extends ConsoleUpdateHistory {

  RegistrarBase registrar;

  // This field exists so that it exists in the SQL table
  @Column(nullable = false)
  @SuppressWarnings("unused")
  private String registrarId;

  public RegistrarBase getRegistrar() {
    return registrar;
  }

  @PostLoad
  void postLoad() {
    registrar.setRegistrarId(registrarId);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<RegistrarUpdateHistory> createVKey() {
    return VKey.create(RegistrarUpdateHistory.class, getRevisionId());
  }

  @Override
  public Builder asBuilder() {
    return new RegistrarUpdateHistory.Builder(clone(this));
  }

  /** Builder for the immutable UserUpdateHistory. */
  public static class Builder
      extends ConsoleUpdateHistory.Builder<RegistrarUpdateHistory, Builder> {

    public Builder() {}

    public Builder(RegistrarUpdateHistory instance) {
      super(instance);
    }

    @Override
    public RegistrarUpdateHistory build() {
      checkArgumentNotNull(getInstance().registrar, "Registrar must be specified");
      return super.build();
    }

    public Builder setRegistrar(RegistrarBase registrar) {
      getInstance().registrar = registrar;
      getInstance().registrarId = registrar.getRegistrarId();
      return this;
    }
  }
}
