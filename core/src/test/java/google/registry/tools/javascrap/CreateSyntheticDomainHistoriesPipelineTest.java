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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;

import com.google.common.collect.Iterables;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.DatastoreEntityExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CreateSyntheticDomainHistoriesPipeline}. */
public class CreateSyntheticDomainHistoriesPipelineTest {

  @RegisterExtension
  JpaTestExtensions.JpaIntegrationTestExtension jpaEextension =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

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
        persistSimpleResource(
            newDomain("example.tld")
                .asBuilder()
                .setNameservers(persistActiveHost("external.com").createVKey())
                .build());
  }

  @Test
  void testSuccess() {
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(DomainHistory.class))).isEmpty();
    CreateSyntheticDomainHistoriesPipeline.setup(pipeline, "NewRegistrar");
    pipeline.run().waitUntilFinish();
    DomainHistory domainHistory = Iterables.getOnlyElement(loadAllOf(DomainHistory.class));
    assertThat(domainHistory.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    assertThat(domainHistory.getRegistrarId()).isEqualTo("NewRegistrar");
    assertAboutImmutableObjects()
        .that(domainHistory.getDomainBase().get())
        .hasFieldsEqualTo(domain);
  }
}
