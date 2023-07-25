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
import com.google.common.collect.ImmutableList;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import google.registry.model.eppcommon.Address;
import java.io.IOException;
import javax.persistence.Embeddable;
import org.apache.commons.lang3.StringUtils;

/**
 * Registrar Address
 *
 * <p>This class is embedded inside a {@link Registrar} object to hold its address. The fields are
 * all defined in parent class {@link Address} so that it can share it with other similar address
 * classes.
 */
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

  public static class RegistrarAddressAdapter extends TypeAdapter<RegistrarAddress> {
    @Override
    public RegistrarAddress read(JsonReader reader) throws IOException {
      Builder builder = new Builder();
      reader.beginObject();
      while (reader.hasNext()) {
        switch (reader.nextName()) {
          case "street":
            builder.setStreet(ImmutableList.of(reader.nextString()));
            break;
          case "city":
            builder.setCity(reader.nextString());
            break;
          case "state":
            builder.setState(reader.nextString());
            break;
          case "zip":
            builder.setZip(reader.nextString());
            break;
          case "countryCode":
            builder.setCountryCode(reader.nextString());
            break;
        }
      }
      reader.endObject();
      return builder.build();
    }

    @Override
    public void write(JsonWriter writer, RegistrarAddress registrarAddress) throws IOException {
      writer.beginObject();
      writer.name("street").value(StringUtils.join(registrarAddress.getStreet(), ""));
      writer.name("city").value(registrarAddress.getCity());
      writer.name("state").value(registrarAddress.getState());
      writer.name("zip").value(registrarAddress.getZip());
      writer.endObject();
    }
  }
}
