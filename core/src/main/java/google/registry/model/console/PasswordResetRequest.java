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
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.persistence.WithVKey;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.util.Optional;
import java.util.UUID;
import org.joda.time.DateTime;

/**
 * Represents a password reset request of some type.
 *
 * <p>Password reset requests must be performed within an hour of the time that they were requested,
 * as well as requiring that the requester and the fulfiller have the proper respective permissions.
 */
@Entity
@WithVKey(String.class)
public class PasswordResetRequest extends ImmutableObject implements Buildable {

  public enum Type {
    EPP,
    REGISTRY_LOCK
  }

  @Id String verificationCode;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  Type type;

  @AttributeOverrides({
    @AttributeOverride(
        name = "creationTime",
        column = @Column(name = "requestTime", nullable = false))
  })
  CreateAutoTimestamp requestTime = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  String requester;

  @Column DateTime fulfillmentTime;

  @Column(nullable = false)
  String destinationEmail;

  @Column(nullable = false)
  String registrarId;

  public String getVerificationCode() {
    return verificationCode;
  }

  public Type getType() {
    return type;
  }

  public DateTime getRequestTime() {
    return requestTime.getTimestamp();
  }

  public String getRequester() {
    return requester;
  }

  public Optional<DateTime> getFulfillmentTime() {
    return Optional.ofNullable(fulfillmentTime);
  }

  public String getDestinationEmail() {
    return destinationEmail;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for constructing immutable {@link PasswordResetRequest} objects. */
  public static class Builder extends Buildable.Builder<PasswordResetRequest> {

    public Builder() {}

    private Builder(PasswordResetRequest instance) {
      super(instance);
    }

    @Override
    public PasswordResetRequest build() {
      checkArgumentNotNull(getInstance().type, "Type must be specified");
      checkArgumentNotNull(getInstance().requester, "Requester must be specified");
      checkArgumentNotNull(getInstance().destinationEmail, "Destination email must be specified");
      checkArgumentNotNull(getInstance().registrarId, "Registrar ID must be specified");
      getInstance().verificationCode = UUID.randomUUID().toString();
      return super.build();
    }

    public Builder setType(Type type) {
      getInstance().type = type;
      return this;
    }

    public Builder setRequester(String requester) {
      getInstance().requester = requester;
      return this;
    }

    public Builder setDestinationEmail(String destinationEmail) {
      getInstance().destinationEmail = destinationEmail;
      return this;
    }

    public Builder setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setFulfillmentTime(DateTime fulfillmentTime) {
      getInstance().fulfillmentTime = fulfillmentTime;
      return this;
    }
  }
}
