// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.CollectionUtils.forceEmptyToNull;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.annotation.Embed;
import google.registry.model.eppcommon.Address;
import javax.persistence.Embeddable;

/**
 * Registrar Address
 *
 * <p>This class is embedded inside a {@link Registrar} object to hold its address. The fields are
 * all defined in parent class {@link Address} so that it can share it with other similar address
 * classes.
 */
@Embed
@Embeddable
public class RegistrarAddress extends Address {

  @Override
  @VisibleForTesting
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for {@link RegistrarAddress}. */
  public static class Builder extends Address.Builder<RegistrarAddress> {
    public Builder() {}

    private Builder(RegistrarAddress instance) {
      super(instance);
    }

    @Override
    public RegistrarAddress build() {
      RegistrarAddress instance = getInstance();
      checkNotNull(forceEmptyToNull(instance.getStreet()), "Missing street");
      checkNotNull(instance.getCity(), "Missing city");
      checkNotNull(instance.getCountryCode(), "Missing country code");
      return super.build();
    }
  }
}
