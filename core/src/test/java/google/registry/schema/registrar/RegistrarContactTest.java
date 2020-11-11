// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarContact.Type.WHOIS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.loadRegistrar;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for persisting {@link RegistrarContact} entities. */
@DualDatabaseTest
class RegistrarContactTest extends EntityTestCase {

  private Registrar testRegistrar;

  private RegistrarContact testRegistrarPoc;

  @BeforeEach
  public void beforeEach() {
    testRegistrar = loadRegistrar("TheRegistrar");
    testRegistrarPoc =
        new RegistrarContact.Builder()
            .setParent(testRegistrar)
            .setName("Judith Registrar")
            .setEmailAddress("judith.doe@example.com")
            .setRegistryLockEmailAddress("judith.doe@external.com")
            .setPhoneNumber("+1.2125650000")
            .setFaxNumber("+1.2125650001")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setVisibleInDomainWhoisAsAbuse(false)
            .build();
  }

  @TestOfyAndSql
  void testPersistence_succeeds() {
    tm().transact(() -> tm().insert(testRegistrarPoc));
    RegistrarContact persisted = tm().transact(() -> tm().load(testRegistrarPoc.createVKey()));
    assertThat(persisted).isEqualTo(testRegistrarPoc);
  }
}
