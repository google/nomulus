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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.common.Cursor;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.nio.file.Path;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link InitSqlPipeline}. */
class InitSqlPipelineTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

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
  }

  @Test
  void runPipeline() {
    InitSqlPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--commitLogStartTimestamp=" + START_TIME,
                "--commitLogEndTimestamp=" + fakeClock.nowUtc().plusMillis(1),
                "--datastoreExportDir=" + setupHelper.exportDir.getAbsolutePath(),
                "--commitLogDir=" + setupHelper.commitLogDir.getAbsolutePath())
            .withValidation()
            .as(InitSqlPipelineOptions.class);
    InitSqlPipeline initSqlPipeline = new InitSqlPipeline(options);
    initSqlPipeline.run(testPipeline).waitUntilFinish();
    assertHostResourceEquals(
        jpaTm().transact(() -> jpaTm().loadByKey(setupHelper.hostResource.createVKey())),
        setupHelper.hostResource);
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(Registrar.class)))
        .comparingElementsUsing(immutableObjectCorrespondence("lastUpdateTime"))
        .containsExactly(setupHelper.registrar1, setupHelper.registrar2);
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(ContactResource.class)))
        .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
        .containsExactly(setupHelper.contact1, setupHelper.contact2);
    assertDomainEquals(
        jpaTm().transact(() -> jpaTm().loadByKey(setupHelper.domain.createVKey())),
        setupHelper.domain);
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(Cursor.class)))
        .comparingElementsUsing(immutableObjectCorrespondence())
        .containsExactly(setupHelper.globalCursor, setupHelper.tldCursor);
  }

  private static void assertHostResourceEquals(HostResource actual, HostResource expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(expected, "superordinateDomain", "revisions", "updateTimestamp");
    assertThat(actual.getSuperordinateDomain().getSqlKey())
        .isEqualTo(expected.getSuperordinateDomain().getSqlKey());
  }

  private static void assertDomainEquals(DomainBase actual, DomainBase expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(
            expected,
            "revisions",
            "updateTimestamp",
            "autorenewPollMessage",
            "deletePollMessage",
            "nsHosts",
            "gracePeriods",
            "transferData");
    assertThat(actual.getAdminContact().getSqlKey())
        .isEqualTo(expected.getAdminContact().getSqlKey());
    assertThat(actual.getRegistrant().getSqlKey()).isEqualTo(expected.getRegistrant().getSqlKey());
    assertThat(actual.getNsHosts()).isEqualTo(expected.getNsHosts());
    assertThat(actual.getAutorenewPollMessage().getOfyKey())
        .isEqualTo(expected.getAutorenewPollMessage().getOfyKey());
    assertThat(actual.getDeletePollMessage().getOfyKey())
        .isEqualTo(expected.getDeletePollMessage().getOfyKey());
    assertThat(actual.getUpdateTimestamp()).isEqualTo(expected.getUpdateTimestamp());
    // TODO(weiminyu): check gracePeriods and transferData when it is easier to do
  }
}
