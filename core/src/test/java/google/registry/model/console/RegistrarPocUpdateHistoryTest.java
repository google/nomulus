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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.Iterables;
import google.registry.model.EntityTestCase;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPoc.RegistrarPocId;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.Test;

/** Tests for {@link RegistrarPocUpdateHistory}. */
public class RegistrarPocUpdateHistoryTest extends EntityTestCase {

  RegistrarPocUpdateHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    RegistrarPoc registrarPoc =
        DatabaseHelper.loadByKey(
            VKey.create(
                RegistrarPoc.class,
                new RegistrarPocId("johndoe@theregistrar.com", "TheRegistrar")));
    RegistrarPocUpdateHistory history =
        new RegistrarPocUpdateHistory.Builder()
            .setRegistrarPoc(registrarPoc)
            .setActingUser(user)
            .setModificationTime(fakeClock.nowUtc())
            .setType(ConsoleUpdateHistory.Type.USER_UPDATE)
            .setMethod("POST")
            .setUrl("someUrl")
            .build();
    tm().transact(() -> tm().put(history));

    // Change the POC and make sure the history stays the same
    tm().transact(() -> tm().put(registrarPoc.asBuilder().setName("Some Othername").build()));

    RegistrarPocUpdateHistory fromDb =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(RegistrarPocUpdateHistory.class));
    assertThat(fromDb.getRegistrarPoc().getName()).isEqualTo("John Doe");
  }
}
