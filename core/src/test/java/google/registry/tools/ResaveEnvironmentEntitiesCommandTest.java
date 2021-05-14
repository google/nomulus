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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;

import com.google.common.collect.ImmutableSortedSet;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResaveEnvironmentEntitiesCommand}. */
class ResaveEnvironmentEntitiesCommandTest
    extends CommandTestCase<ResaveEnvironmentEntitiesCommand> {

  @Test
  void testSuccess_noop() throws Exception {
    // Get rid of all the entities that this command runs on so that it does nothing.
    deleteEntitiesOfTypes(
        Registry.class,
        Registrar.class,
        RegistrarContact.class,
        CommitLogManifest.class,
        CommitLogMutation.class);
    runCommand();
    assertThat(auditedOfy().load().type(CommitLogManifest.class).keys()).isEmpty();
    assertThat(auditedOfy().load().type(CommitLogMutation.class).keys()).isEmpty();
  }

  @Test
  void testSuccess_createsCommitLogs() throws Exception {
    createTld("tld");
    deleteEntitiesOfTypes(CommitLogManifest.class, CommitLogMutation.class);
    assertThat(auditedOfy().load().type(CommitLogManifest.class).keys()).isEmpty();
    assertThat(auditedOfy().load().type(CommitLogMutation.class).keys()).isEmpty();
    runCommand();

    // There are 5 entities that have been re-saved at this point (in 3 transactions, one for each
    // type), so expect 3 manifests and 5 mutations.
    assertThat(auditedOfy().load().type(CommitLogManifest.class).keys()).hasSize(3);
    Iterable<ImmutableObject> savedEntities =
        transform(
            auditedOfy().load().type(CommitLogMutation.class).list(),
            mutation -> auditedOfy().load().fromEntity(mutation.getEntity()));
    ImmutableSortedSet<RegistrarContact> theRegistrarContacts =
        loadRegistrar("TheRegistrar").getContacts();
    assertThat(savedEntities)
        .containsExactly(
            // The Registrars and RegistrarContacts are created by AppEngineRule.
            loadRegistrar("TheRegistrar"),
            loadRegistrar("NewRegistrar"),
            Registry.get("tld"),
            theRegistrarContacts.first(),
            theRegistrarContacts.last(),
            getOnlyElement(loadRegistrar("NewRegistrar").getContacts()));
  }

  @SafeVarargs
  private static void deleteEntitiesOfTypes(Class<? extends ImmutableObject>... types) {
    for (Class<? extends ImmutableObject> type : types) {
      auditedOfy().deleteWithoutBackup().keys(auditedOfy().load().type(type).keys()).now();
    }
  }
}
