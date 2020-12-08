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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import google.registry.backup.VersionedEntity;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.PersistenceTestModule;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.apache.beam.sdk.transforms.Create;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit test for {@link Transforms#writeToSql}. */
class WriteToSqlTest implements Serializable {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @RegisterExtension final transient InjectExtension injectRule = new InjectExtension();

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  @SuppressWarnings("WeakerAccess")
  @TempDir
  transient Path tmpDir;

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  // Must not be transient!
  @RegisterExtension
  @Order(Order.DEFAULT + 1)
  final BeamJpaExtension beamJpaExtension =
      new BeamJpaExtension(() -> tmpDir.resolve("credential.dat"), database.getDatabase());

  private ImmutableList<Entity> contacts;

  @BeforeEach
  void beforeEach() throws Exception {
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectRule.setStaticField(Ofy.class, "clock", fakeClock);

      // Required for contacts created below.
      Registrar ofyRegistrar = AppEngineExtension.makeRegistrar2();
      store.insertOrUpdate(ofyRegistrar);
      jpaTm().transact(() -> jpaTm().put(store.loadAsOfyEntity(ofyRegistrar)));

      ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();

      for (int i = 0; i < 3; i++) {
        ContactResource contact = DatabaseHelper.newContactResource("contact_" + i);
        store.insertOrUpdate(contact);
        builder.add(store.loadAsDatastoreEntity(contact));
      }
      contacts = builder.build();
    }
  }

  @Test
  void writeToSql_twoWriters() {
    testPipeline
        .apply(
            Create.of(
                contacts.stream()
                    .map(InitSqlTestUtils::entityToBytes)
                    .map(bytes -> VersionedEntity.from(0L, bytes))
                    .collect(Collectors.toList())))
        .apply(
            Transforms.writeToSql(
                "ContactResource",
                2,
                4,
                () ->
                    PersistenceTestModule.testJpaTransactionManager(
                        beamJpaExtension.getBeamJpaModule())));
    testPipeline.run().waitUntilFinish();

    ImmutableList<?> sqlContacts = jpaTm().transact(() -> jpaTm().loadAllOf(ContactResource.class));
    assertThat(sqlContacts)
        .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
        .containsExactlyElementsIn(
            contacts.stream()
                .map(InitSqlTestUtils::datastoreToOfyEntity)
                .map(ImmutableObject.class::cast)
                .collect(ImmutableList.toImmutableList()));
  }
}
