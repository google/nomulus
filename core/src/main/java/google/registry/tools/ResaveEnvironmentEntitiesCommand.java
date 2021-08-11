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

import static com.google.common.collect.Lists.partition;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameters;
import com.googlecode.objectify.Key;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.tld.Registry;

/**
 * Command to re-save all environment entities to ensure that they have valid commit logs.
 *
 * <p>The entities that are re-saved are those of type {@link Registry}, {@link Registrar}, and
 * {@link RegistrarContact}.
 */
@Parameters(commandDescription = "Re-save all environment entities.")
final class ResaveEnvironmentEntitiesCommand implements CommandWithRemoteApi {

  private static final int BATCH_SIZE = 10;

  @Override
  public void run() {
    batchSave(Registry.class);
    batchSave(Registrar.class);
    batchSave(RegistrarContact.class);
  }

  private static <T> void batchSave(Class<T> clazz) {
    System.out.printf("Re-saving %s entities.\n", clazz.getSimpleName());
    for (final Iterable<Key<T>> batch :
        partition(
            auditedOfy().load().type(clazz).ancestor(getCrossTldKey()).keys().list(), BATCH_SIZE)) {
      tm().transact(() -> auditedOfy().save().entities(auditedOfy().load().keys(batch).values()));
      System.out.printf("Re-saved entities batch: %s.\n", batch);
    }
  }
}
