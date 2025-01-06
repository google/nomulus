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

package google.registry.model.registrar;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Information about a registrar. */
@Access(AccessType.FIELD)
@Entity
@Table(
    indexes = {
      @Index(columnList = "registrarName", name = "registrar_name_idx"),
      @Index(columnList = "ianaIdentifier", name = "registrar_iana_identifier_idx"),
    })
@AttributeOverride(
    name = "updateTimestamp.lastUpdateTime",
    column = @Column(nullable = false, name = "lastUpdateTime"))
public class Registrar extends RegistrarBase {

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public String getRegistrarId() {
    return super.getRegistrarId();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for constructing immutable {@link Registrar} objects. */
  public static class Builder extends RegistrarBase.Builder<Registrar, Builder> {

    public Builder() {}

    public Builder(Registrar registrar) {
      super(registrar);
    }

  }
}
