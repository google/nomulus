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
import static google.registry.testing.DatabaseHelper.existsInDb;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.updateInDb;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for persisting {@link Registrar} entities. */
public class RegistrarDaoTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  @Order(value = 1)
  DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private final VKey<Registrar> registrarKey = VKey.createSql(Registrar.class, "registrarId");

  private Registrar testRegistrar;

  @BeforeEach
  void setUp() {
    testRegistrar =
        new Registrar.Builder()
            .setType(Registrar.Type.TEST)
            .setRegistrarId("registrarId")
            .setRegistrarName("registrarName")
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 Example Boulevard."))
                    .setCity("Williamsburg")
                    .setState("NY")
                    .setZip("11211")
                    .setCountryCode("US")
                    .build())
            .build();
  }

  @Test
  void saveNew_worksSuccessfully() {
    assertThat(existsInDb(testRegistrar)).isFalse();
    insertInDb(testRegistrar);
    assertThat(existsInDb(testRegistrar)).isTrue();
  }

  @Test
  void update_worksSuccessfully() {
    insertInDb(testRegistrar);
    Registrar persisted = loadByKey(registrarKey);
    assertThat(persisted.getRegistrarName()).isEqualTo("registrarName");
    updateInDb(persisted.asBuilder().setRegistrarName("changedRegistrarName").build());
    Registrar updated = loadByKey(registrarKey);
    assertThat(updated.getRegistrarName()).isEqualTo("changedRegistrarName");
  }

  @Test
  void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(existsInDb(testRegistrar)).isFalse();
    assertThrows(IllegalArgumentException.class, () -> updateInDb(testRegistrar));
  }

  @Test
  void load_worksSuccessfully() {
    assertThat(existsInDb(testRegistrar)).isFalse();
    insertInDb(testRegistrar);
    Registrar persisted = loadByKey(registrarKey);

    assertThat(persisted.getRegistrarId()).isEqualTo("registrarId");
    assertThat(persisted.getRegistrarName()).isEqualTo("registrarName");
    assertThat(persisted.getCreationTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(persisted.getLocalizedAddress())
        .isEqualTo(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("123 Example Boulevard."))
                .setCity("Williamsburg")
                .setState("NY")
                .setZip("11211")
                .setCountryCode("US")
                .build());
  }
}
