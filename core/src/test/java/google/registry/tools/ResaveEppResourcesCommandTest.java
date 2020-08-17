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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistActiveContact;

import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResaveEppResourceCommand}. */
class ResaveEppResourcesCommandTest extends CommandTestCase<ResaveEppResourceCommand> {

  @Test
  void testSuccess_createsCommitLogs() throws Exception {
    ContactResource contact = persistActiveContact("contact");
    deleteEntitiesOfTypes(CommitLogManifest.class, CommitLogMutation.class);
    assertThat(ofy().load().type(CommitLogManifest.class).keys()).isEmpty();
    assertThat(ofy().load().type(CommitLogMutation.class).keys()).isEmpty();
    runCommandForced("--type=CONTACT", "--id=contact");

    assertThat(ofy().load().type(CommitLogManifest.class).keys()).hasSize(1);
    assertThat(ofy().load().type(CommitLogMutation.class).keys()).hasSize(1);
    CommitLogMutation mutation = ofy().load().type(CommitLogMutation.class).first().now();
    // Reload the contact before asserting, since its update time will have changed.
    ofy().clearSessionCache();
    assertThat(ofy().load().<Object>fromEntity(mutation.getEntity()))
        .isEqualTo(ofy().load().entity(contact).now());
  }

  @SafeVarargs
  private static void deleteEntitiesOfTypes(Class<? extends ImmutableObject>... types) {
    for (Class<? extends ImmutableObject> type : types) {
      ofy().deleteWithoutBackup().keys(ofy().load().type(type).keys()).now();
    }
  }
}
