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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.registrar.RegistrarPoc.Type.WHOIS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.SqlHelper.saveRegistrar;

import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for persisting {@link RegistrarPoc} entities. */
class RegistrarPocTest {

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationWithCoverageExtension();

  private Registrar testRegistrar;

  private RegistrarPoc testRegistrarPoc;

  @BeforeEach
  public void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    testRegistrarPoc =
        new RegistrarPoc.Builder()
            .setRegistrar(testRegistrar)
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

  @Test
  void testPersistence_succeeds() {
    insertInDb(testRegistrarPoc);
    assertAboutImmutableObjects()
        .that(testRegistrarPoc)
        .isEqualExceptFields(testRegistrarPoc, "id");
  }

  @Test
  void testSerializable_succeeds() {
    insertInDb(testRegistrarPoc);
    RegistrarPoc persisted = tm().transact(() -> tm().loadByEntity(testRegistrarPoc));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }
}
