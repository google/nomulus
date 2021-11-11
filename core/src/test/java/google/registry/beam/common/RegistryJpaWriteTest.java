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

package google.registry.beam.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.putInDb;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import google.registry.backup.VersionedEntity;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.initsql.BackupTestStore;
import google.registry.beam.initsql.InitSqlTestUtils;
import google.registry.beam.initsql.Transforms;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.io.Serializable;
import java.util.stream.Collectors;
import org.apache.beam.sdk.transforms.Create;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link RegistryJpaIO.Write}. */
class RegistryJpaWriteTest implements Serializable {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension final transient InjectExtension injectExtension = new InjectExtension();

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private ImmutableList<Entity> contacts;

  @BeforeEach
  void beforeEach() throws Exception {
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectExtension.setStaticField(Ofy.class, "clock", fakeClock);

      // Required for contacts created below.
      Registrar ofyRegistrar = AppEngineExtension.makeRegistrar2();
      store.insertOrUpdate(ofyRegistrar);
      putInDb(store.loadAsOfyEntity(ofyRegistrar));

      ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();

      for (int i = 0; i < 3; i++) {
        ContactResource contact = newContactResource("contact_" + i);
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
            RegistryJpaIO.<VersionedEntity>write()
                .withName("ContactResource")
                .withBatchSize(4)
                .withShards(2)
                .withJpaConverter(Transforms::convertVersionedEntityToSqlEntity));
    testPipeline.run().waitUntilFinish();

    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(ContactResource.class)))
        .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
        .containsExactlyElementsIn(
            contacts.stream()
                .map(InitSqlTestUtils::datastoreToOfyEntity)
                .map(ImmutableObject.class::cast)
                .collect(ImmutableList.toImmutableList()));
  }
}
