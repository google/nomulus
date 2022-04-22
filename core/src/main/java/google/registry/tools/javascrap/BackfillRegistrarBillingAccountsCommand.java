// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.OteAccountBuilder.DEFAULT_BILLING_ACCOUNT_MAP;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameters;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.tools.CommandWithRemoteApi;

/**
 * Backfills the billing account maps on all Registrars that don't have any set.
 *
 * <p>This should not (and cannot) be used on production. Its purpose is to backfill these values on
 * sandbox, where most registrars have an empty billing account map, including all that were created
 * for OT&amp;E. The same default billing account map is used for all registrars, and includes
 * values for USD and JPY. The actual values here don't matter as we don't do invoicing on sandbox
 * anyway.
 */
@Parameters(separators = " =", commandDescription = "Backfill registrar billing account maps.")
public class BackfillRegistrarBillingAccountsCommand implements CommandWithRemoteApi {

  @Override
  public void run() throws Exception {
    checkState(
        RegistryEnvironment.get() != RegistryEnvironment.PRODUCTION,
        "Do not run this on production");
    System.out.println("Populating billing account maps on all registrars missing them ...");
    tm().transact(
            () ->
                tm().loadAllOfStream(Registrar.class)
                    .filter(r -> r.getBillingAccountMap().isEmpty())
                    .forEach(
                        r ->
                            tm().update(
                                    r.asBuilder()
                                        .setBillingAccountMap(DEFAULT_BILLING_ACCOUNT_MAP)
                                        .build())));
    System.out.println("Done!");
  }
}
