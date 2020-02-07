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

package google.registry.tools;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameters;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.schema.registrar.RegistrarDao;
import javax.annotation.Nullable;

/** Command to update a Registrar. */
@Parameters(separators = " =", commandDescription = "Update registrar account(s)")
final class UpdateRegistrarCommand extends CreateOrUpdateRegistrarCommand {

  @Override
  Registrar getOldRegistrar(String clientId) {
    return checkArgumentPresent(
        Registrar.loadByClientId(clientId), "Registrar %s not found", clientId);
  }

  @Override
  void checkModifyAllowedTlds(@Nullable Registrar oldRegistrar) {
    // Only allow modifying allowed TLDs if we're in a non-PRODUCTION environment, if the registrar
    // is not REAL, or the registrar has a WHOIS abuse contact set.
    checkArgumentNotNull(oldRegistrar, "Old registrar was not present during modification");

    boolean isRealRegistrar =
        Registrar.Type.REAL.equals(registrarType)
            || (Registrar.Type.REAL.equals(oldRegistrar.getType()) && registrarType == null);
    if (RegistryEnvironment.PRODUCTION.equals(RegistryEnvironment.get()) && isRealRegistrar) {
      checkArgumentPresent(
          oldRegistrar.getWhoisAbuseContact(),
          "Cannot modify allowed TLDs if there is no WHOIS abuse contact set. Please use the"
              + " \"nomulus registrar_contact\" command on this registrar to set a WHOIS abuse"
              + " contact.");
    }
  }

  @Override
  void saveToCloudSql(Registrar registrar) {
    RegistrarDao.update(registrar);
  }
}
