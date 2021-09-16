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

package google.registry.tools.javascrap;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.tools.MutatingCommand;
import java.util.Objects;

/**
 * Scrap tool to update Registrars with null registrarName or localizedAddress fields.
 *
 * <p>This sets a null registrarName to the key name, and null localizedAddress fields to fake data.
 */
@Parameters(
  separators = " =",
  commandDescription = "Populate previously null required registrar fields."
)
public class PopulateNullRegistrarFieldsCommand extends MutatingCommand {

  @Override
  protected void init() {
    for (Registrar registrar : Registrar.loadAll()) {
      Registrar.Builder changeBuilder = registrar.asBuilder();
      changeBuilder.setRegistrarName(
          firstNonNull(registrar.getRegistrarName(), registrar.getRegistrarId()));

      RegistrarAddress address = registrar.getLocalizedAddress();
      if (address == null) {
        changeBuilder.setLocalizedAddress(
            new RegistrarAddress.Builder()
                .setCity("Fakington")
                .setCountryCode("US")
                .setState("FL")
                .setZip("12345")
                .setStreet(ImmutableList.of("123 Fake Street"))
                .build());
      } else {
        changeBuilder.setLocalizedAddress(
            new RegistrarAddress.Builder()
                .setCity(firstNonNull(address.getCity(), "Fakington"))
                .setCountryCode(firstNonNull(address.getCountryCode(), "US"))
                .setState(firstNonNull(address.getState(), "FL"))
                .setZip(firstNonNull(address.getZip(), "12345"))
                .setStreet(firstNonNull(address.getStreet(), ImmutableList.of("123 Fake Street")))
                .build());
      }
      Registrar changedRegistrar = changeBuilder.build();
      if (!Objects.equals(registrar, changedRegistrar)) {
        stageEntityChange(registrar, changedRegistrar);
      }
    }
  }
}
