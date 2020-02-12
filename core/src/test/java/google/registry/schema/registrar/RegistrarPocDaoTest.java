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
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static org.junit.Assert.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.testing.FakeClock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistrarPocDao}. */
@RunWith(JUnit4.class)
public class RegistrarPocDaoTest extends EntityTestCase {
  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaTestRules.JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageRule();

  private Registrar testRegistrar;
  private RegistrarContact testRegistrarContact;

  @Before
  public void setUp() {
    testRegistrar = loadRegistrar("TheRegistrar");
    testRegistrarContact =
        new RegistrarContact.Builder()
            .setParent(testRegistrar)
            .setEmailAddress("contact@registrar.google")
            .setName("theRegistrarPoc")
            .build();
  }

  @Test
  public void saveNew_worksSuccessfully() {
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isFalse();
    RegistrarPocDao.saveNew(testRegistrarContact);
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isTrue();
  }

  @Test
  public void update_worksSuccessfully() {
    RegistrarPocDao.saveNew(testRegistrarContact);
    RegistrarContact persisted = RegistrarPocDao.load("contact@registrar.google").get();
    assertThat(persisted.getName()).isEqualTo("theRegistrarPoc");
    RegistrarPocDao.update(
        persisted.asBuilder().setParent(testRegistrar).setName("newRegistrarPoc").build());
    persisted = RegistrarPocDao.load("contact@registrar.google").get();
    assertThat(persisted.getName()).isEqualTo("newRegistrarPoc");
  }

  @Test
  public void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isFalse();
    assertThrows(
        IllegalArgumentException.class, () -> RegistrarPocDao.update(testRegistrarContact));
  }

  @Test
  public void load_worksSuccessfully() {
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isFalse();
    RegistrarPocDao.saveNew(testRegistrarContact);
    RegistrarContact persisted = RegistrarPocDao.load("contact@registrar.google").get();

    assertThat(persisted.getEmailAddress()).isEqualTo("contact@registrar.google");
    assertThat(persisted.getName()).isEqualTo("theRegistrarPoc");
  }

  @Test
  public void delete_worksSuccessfully() {
    RegistrarPocDao.saveNew(testRegistrarContact);
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isTrue();
    RegistrarPocDao.delete("contact@registrar.google");
    assertThat(RegistrarPocDao.checkExists("contact@registrar.google")).isFalse();
  }
}
