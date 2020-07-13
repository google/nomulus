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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import google.registry.backup.VersionedEntity;
import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.File;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.stream.Collectors;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test for {@link Transforms#writeToSql}. */
@RunWith(JUnit4.class)
public class WriteToSqlTest implements Serializable {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public transient JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  @Rule public transient TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public final transient TestPipeline pipeline =
      TestPipeline.create().enableAbandonedNodeEnforcement(true);

  private ImmutableList<Entity> contacts;

  private File credentialFile;

  @Before
  public void beforeEach() throws Exception {
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectRule.setStaticField(Ofy.class, "clock", fakeClock);

      // Required for contacts created below.
      Registrar ofyRegistrar = AppEngineRule.makeRegistrar2();
      store.insertOrUpdate(ofyRegistrar);
      jpaTm().transact(() -> jpaTm().saveNewOrUpdate(store.loadAsOfyEntity(ofyRegistrar)));

      ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();

      for (int i = 0; i < 3; i++) {
        ContactResource contact = DatastoreHelper.newContactResource("contact_" + i);
        store.insertOrUpdate(contact);
        builder.add(store.loadAsDatastoreEntity(contact));
      }
      contacts = builder.build();
    }
    credentialFile = temporaryFolder.newFile();
    new PrintStream(credentialFile)
        .printf(
            "%s %s %s",
            jpaRule.getDatabaseUrl(), jpaRule.getDatabaseUsername(), jpaRule.getDatabasePassword())
        .close();
  }

  @Test
  @Category(NeedsRunner.class)
  public void writeToSql_twoWriters() {
    pipeline
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
                    DaggerBeamJpaModule_JpaTransactionManagerComponent.builder()
                        .beamJpaModule(new BeamJpaModule(credentialFile.getAbsolutePath()))
                        .build()
                        .localDbJpaTransactionManager()));
    pipeline.run().waitUntilFinish();

    ImmutableList<?> sqlContacts = jpaTm().transact(() -> jpaTm().loadAll(ContactResource.class));
    // TODO(weiminyu): compare load entities with originals. Note: lastUpdateTimes won't match by
    // design. Need an elegant way to deal with this.bbq
    assertThat(sqlContacts).hasSize(3);
  }
}
