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

package google.registry.beam.comparedb;

import static google.registry.beam.comparedb.ValidateSqlUtils.createSqlEntityTupleTag;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.bulkquery.TestSetupHelper;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.replay.SqlEntity;
import google.registry.model.tld.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import java.util.Optional;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link SqlSnapshots}. */
class SqlSnapshotsTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withClock(fakeClock).build();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private final TestSetupHelper setupHelper = new TestSetupHelper(fakeClock);

  @BeforeEach
  void setUp() {
    testPipeline
        .getCoderRegistry()
        .registerCoderForClass(SqlEntity.class, SerializableCoder.of(Serializable.class));
    setupHelper.initializeAllEntities();
    setupHelper.setupBulkQueryJpaTm(appEngine);
  }

  @AfterEach
  void afterEach() {
    setupHelper.tearDownBulkQueryJpaTm();
  }

  @Test
  void loadCloudSqlSnapshotByType() {
    PCollectionTuple sqlSnapshot =
        SqlSnapshots.loadCloudSqlSnapshotByType(
            testPipeline,
            ImmutableSet.of(
                Registry.class,
                Registrar.class,
                DomainBase.class,
                DomainHistory.class,
                ContactResource.class,
                HostResource.class),
            Optional.empty(),
            Optional.empty());
    PAssert.that(sqlSnapshot.get(createSqlEntityTupleTag(Registry.class)))
        .containsInAnyOrder(setupHelper.registry);
    PAssert.that(sqlSnapshot.get(createSqlEntityTupleTag(DomainBase.class)))
        .containsInAnyOrder(setupHelper.domain);
    PAssert.that(sqlSnapshot.get(createSqlEntityTupleTag(DomainHistory.class)))
        .containsInAnyOrder(setupHelper.domainHistory);
    testPipeline.run();
  }
}
