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


import com.google.common.annotations.VisibleForTesting;
import google.registry.model.ImmutableObject;
import google.registry.model.registrar.RegistrarPoc.RegistrarPocId;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;

/**
 * A contact for a Registrar. Note, equality, hashCode and comparable have been overridden to only
 * enable key equality.
 *
 * <p>IMPORTANT NOTE: Any time that you change, update, or delete RegistrarContact entities, you
 * *MUST* also modify the persisted Registrar entity with {@link Registrar#contactsRequireSyncing}
 * set to true.
 */
@Entity
@IdClass(RegistrarPocId.class)
@Access(AccessType.FIELD)
public class RegistrarPoc extends RegistrarPocBase {

  @Id
  @Access(AccessType.PROPERTY)
  @Override
  public String getEmailAddress() {
    return emailAddress;
  }

  @Id
  @Access(AccessType.PROPERTY)
  public String getRegistrarId() {
    return registrarId;
  }

  @Override
  public VKey<RegistrarPoc> createVKey() {
    return VKey.create(RegistrarPoc.class, new RegistrarPocId(emailAddress, registrarId));
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Class to represent the composite primary key for {@link RegistrarPoc} entity. */
  @VisibleForTesting
  public static class RegistrarPocId extends ImmutableObject implements Serializable {

    String emailAddress;

    String registrarId;

    // Hibernate requires this default constructor.
    @SuppressWarnings("unused")
    private RegistrarPocId() {}

    @VisibleForTesting
    public RegistrarPocId(String emailAddress, String registrarId) {
      this.emailAddress = emailAddress;
      this.registrarId = registrarId;
    }

    @Id
    public String getEmailAddress() {
      return emailAddress;
    }

    @Id
    public String getRegistrarId() {
      return registrarId;
    }
  }

  public static class Builder extends RegistrarPocBase.Builder<RegistrarPoc, Builder> {

    public Builder() {}

    public Builder(RegistrarPoc registrarPoc) {
      super(registrarPoc);
    }
  }
}
