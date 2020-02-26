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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.EntityTestCase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.testing.FakeClock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistrarDao}. */
@RunWith(JUnit4.class)
public class RegistrarDaoTest extends EntityTestCase {
  private final VKey<Registrar> registrarKey = VKey.create(Registrar.class, "registrarId");
  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageRule();

  private Registrar testRegistrar;

  @Before
  public void setUp() {
    testRegistrar =
        new Registrar.Builder()
            .setType(Registrar.Type.TEST)
            .setClientId("registrarId")
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
  public void saveNew_worksSuccessfully() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(testRegistrar))).isFalse();
    jpaTm().transact(() -> jpaTm().create(testRegistrar));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(testRegistrar))).isTrue();
  }

  @Test
  public void update_worksSuccessfully() {
    jpaTm().transact(() -> jpaTm().create(testRegistrar));
    Registrar persisted = jpaTm().transact(() -> jpaTm().load(registrarKey)).get();
    assertThat(persisted.getRegistrarName()).isEqualTo("registrarName");
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .update(
                        persisted.asBuilder().setRegistrarName("changedRegistrarName").build()));
    Registrar updated = jpaTm().transact(() -> jpaTm().load(registrarKey)).get();
    assertThat(updated.getRegistrarName()).isEqualTo("changedRegistrarName");
  }

  @Test
  public void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(testRegistrar))).isFalse();
    assertThrows(
        IllegalArgumentException.class,
        () -> jpaTm().transact(() -> jpaTm().update(testRegistrar)));
  }

  @Test
  public void load_worksSuccessfully() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(testRegistrar))).isFalse();
    jpaTm().transact(() -> jpaTm().create(testRegistrar));
    Registrar persisted = jpaTm().transact(() -> jpaTm().load(registrarKey)).get();

    assertThat(persisted.getClientId()).isEqualTo("registrarId");
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
