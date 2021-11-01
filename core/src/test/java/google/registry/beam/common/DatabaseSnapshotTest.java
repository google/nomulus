// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Truth;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.tld.Registry;
import google.registry.persistence.NomulusPostgreSql;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import javax.persistence.Persistence;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.values.PCollection;
import org.hibernate.cfg.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Unit tests for {@link DatabaseSnapshot}. */
@Testcontainers
public class DatabaseSnapshotTest {

  /**
   * For reasons unknown, an EntityManagerFactory created by {@code JpaIntegrationTestExtension} or
   * {@code JpaUnitTestExtension} enters a bad state after exporting the first snapshot. Starting
   * with the second attempt, exports alternate between error ("cannot export a snapshot from a
   * subtransaction") and success. The {@link #createSnapshot_twiceNoRead} test below fails with
   * either extension. EntityManagerFactory created for production does not have this problem.
   */
  @Container
  private static PostgreSQLContainer sqlContainer =
      new PostgreSQLContainer<>(NomulusPostgreSql.getDockerTag())
          .withInitScript("sql/schema/nomulus.golden.sql");

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  static JpaTransactionManager origJpa;
  static JpaTransactionManager jpa;

  static Registry registry;

  @BeforeAll
  static void setup() {
    ImmutableMap<String, String> jpaProperties =
        new ImmutableMap.Builder<String, String>()
            .put(Environment.URL, sqlContainer.getJdbcUrl())
            .put(Environment.USER, sqlContainer.getUsername())
            .put(Environment.PASS, sqlContainer.getPassword())
            .putAll(PersistenceModule.provideDefaultDatabaseConfigs())
            .build();
    jpa =
        new JpaTransactionManagerImpl(
            Persistence.createEntityManagerFactory("nomulus", jpaProperties), new FakeClock());
    origJpa = jpaTm();
    TransactionManagerFactory.setJpaTm(() -> jpa);

    Registry tld = DatabaseHelper.newRegistry("tld", "TLD");
    jpaTm().transact(() -> jpaTm().put(tld));
    registry = jpaTm().transact(() -> jpaTm().loadByEntity(tld));
  }

  @AfterAll
  static void tearDown() {
    TransactionManagerFactory.setJpaTm(() -> origJpa);

    if (jpa != null) {
      jpa.teardown();
    }
  }

  @Test
  void createSnapshot_onceNoRead() {
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {}
  }

  @Test
  void createSnapshot_twiceNoRead() {
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {}
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {}
  }

  @Test
  void readSnapshot() {
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
      Registry snapshotRegistry =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .setDatabaseSnapshot(databaseSnapshot.getSnapshotId())
                          .loadByEntity(registry));
      Truth.assertThat(snapshotRegistry).isEqualTo(registry);
    }
  }

  @Test
  void readSnapshot_withSubsequentChange() {
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
      Registry updated =
          registry
              .asBuilder()
              .setCreateBillingCost(registry.getStandardCreateCost().plus(1))
              .build();
      jpaTm().transact(() -> jpaTm().put(updated));

      Registry persistedUpdate = jpaTm().transact(() -> jpaTm().loadByEntity(registry));
      Truth.assertThat(persistedUpdate).isNotEqualTo(registry);

      Registry snapshotRegistry =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .setDatabaseSnapshot(databaseSnapshot.getSnapshotId())
                          .loadByEntity(registry));
      Truth.assertThat(snapshotRegistry).isEqualTo(registry);
    } finally {
      // Revert change to registry in DB, which is shared by all test methods.
      jpaTm().transact(() -> jpaTm().put(registry));
    }
  }

  @Test
  void readWithRegistryJpaIO() {
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
      Registry updated =
          registry
              .asBuilder()
              .setCreateBillingCost(registry.getStandardCreateCost().plus(1))
              .build();
      jpaTm().transact(() -> jpaTm().put(updated));

      Read<Registry, Registry> read =
          RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(Registry.class).build(), x -> x)
              .withSnapshot(databaseSnapshot.getSnapshotId());
      PCollection<Registry> registries = testPipeline.apply(read);

      // This assertion depends on Registry being Serializable, which may change if the
      // UnsafeSerializable interface is removed after migration.
      PAssert.that(registries).containsInAnyOrder(registry);
      testPipeline.run();
    } finally {
      // Revert change to registry in DB, which is shared by all test methods.
      jpaTm().transact(() -> jpaTm().put(registry));
    }
  }
}
