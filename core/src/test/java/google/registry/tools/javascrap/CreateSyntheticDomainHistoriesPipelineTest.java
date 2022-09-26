// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CreateSyntheticDomainHistoriesPipeline}. */
public class CreateSyntheticDomainHistoriesPipelineTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2022-09-01T00:00:00.000Z"));

  @RegisterExtension
  JpaTestExtensions.JpaIntegrationTestExtension jpaEextension =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  DatastoreEntityExtension datastoreEntityExtension =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension TestPipelineExtension pipeline = TestPipelineExtension.create();

  private Domain domain;

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("TheRegistrar");
    persistNewRegistrar("NewRegistrar");
    createTld("tld");
    domain =
        persistDomainWithDependentResources(
            "example",
            "tld",
            persistResource(newContact("contact1234")),
            fakeClock.nowUtc(),
            DateTime.parse("2022-09-01T00:00:00.000Z"),
            DateTime.parse("2024-09-01T00:00:00.000Z"));
    domain =
        persistSimpleResource(
            domain
                .asBuilder()
                .setNameservers(persistActiveHost("external.com").createVKey())
                .build());
    fakeClock.setTo(DateTime.parse("2022-09-20T00:00:00.000Z"));
    // shouldn't create any history objects for this domain
    persistDomainWithDependentResources(
        "ignored-example",
        "tld",
        persistResource(newContact("contact12345")),
        fakeClock.nowUtc(),
        DateTime.parse("2022-09-20T00:00:00.000Z"),
        DateTime.parse("2024-09-20T00:00:00.000Z"));
  }

  @Test
  void testSuccess() {
    assertThat(loadAllOf(DomainHistory.class)).hasSize(2);
    CreateSyntheticDomainHistoriesPipeline.setup(pipeline, "NewRegistrar");
    pipeline.run().waitUntilFinish();
    DomainHistory syntheticHistory =
        HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey(), DomainHistory.class)
            .get(1);
    assertThat(syntheticHistory.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    assertThat(syntheticHistory.getRegistrarId()).isEqualTo("NewRegistrar");
    assertAboutImmutableObjects()
        .that(syntheticHistory.getDomainBase().get())
        .isEqualExceptFields(domain, "updateTimestamp");

    // shouldn't create any entries on re-run
    pipeline.run().waitUntilFinish();
    assertThat(HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey())).hasSize(2);
    // three total histories, two CREATE and one SYNTHETIC
    assertThat(loadAllOf(DomainHistory.class)).hasSize(3);
  }
}
