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

import com.google.common.collect.ImmutableSet;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.initsql.DatastoreSetupHelper;
import google.registry.model.domain.DomainHistory;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DatastoreSnapshots}. */
class DatastoreSnapshotsTest {
  static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension final transient InjectExtension injectExtension = new InjectExtension();

  @SuppressWarnings("WeakerAccess")
  @TempDir
  transient Path tmpDir;

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  DatastoreSetupHelper setupHelper;

  @BeforeEach
  void beforeEach() throws Exception {
    injectExtension.setStaticField(Ofy.class, "clock", fakeClock);
    setupHelper = new DatastoreSetupHelper(tmpDir, fakeClock).initializeData();
    testPipeline
        .getCoderRegistry()
        .registerCoderForClass(SqlEntity.class, SerializableCoder.of(Serializable.class));
  }

  @Test
  void loadDatastoreSnapshotByKind() {
    PCollectionTuple tuple =
        DatastoreSnapshots.loadDatastoreSnapshotByKind(
            testPipeline,
            setupHelper.exportDir.getAbsolutePath(),
            setupHelper.commitLogDir.getAbsolutePath(),
            START_TIME,
            fakeClock.nowUtc().plusMillis(1),
            ImmutableSet.copyOf(DatastoreSetupHelper.ALL_KINDS),
            Optional.empty());
    PAssert.that(tuple.get(ValidateSqlUtils.createSqlEntityTupleTag(Registrar.class)))
        .containsInAnyOrder(setupHelper.registrar1, setupHelper.registrar2);
    PAssert.that(tuple.get(ValidateSqlUtils.createSqlEntityTupleTag(DomainHistory.class)))
        .containsInAnyOrder(setupHelper.historyEntry);
    testPipeline.run();
  }
}
