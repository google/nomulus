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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.removeTmOverrideForTest;
import static google.registry.persistence.transaction.TransactionManagerFactory.setTmOverrideForTest;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;

import com.google.common.collect.ImmutableList;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CreateSyntheticHistoryEntriesPipeline}. */
public class CreateSyntheticHistoryEntriesPipelineTest {

  @RegisterExtension
  JpaIntegrationTestExtension jpaEextension =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @RegisterExtension
  DatastoreEntityExtension datastoreEntityExtension =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension TestPipelineExtension pipeline = TestPipelineExtension.create();

  DomainBase domain;
  ContactResource contact;
  HostResource host;

  @BeforeEach
  void beforeEach() {
    setTmOverrideForTest(jpaTm());
    persistNewRegistrar("TheRegistrar");
    persistNewRegistrar("NewRegistrar");
    createTld("tld");
    host = persistActiveHost("external.com");
    domain =
        persistSimpleResource(
            newDomainBase("example.tld").asBuilder().setNameservers(host.createVKey()).build());
    contact = jpaTm().transact(() -> jpaTm().loadByKey(domain.getRegistrant()));
  }

  @AfterEach
  void afterEach() {
    removeTmOverrideForTest();
  }

  @Test
  void testSuccess() {
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(DomainHistory.class))).isEmpty();
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(ContactHistory.class))).isEmpty();
    assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(HostHistory.class))).isEmpty();
    CreateSyntheticHistoryEntriesPipeline.setup(pipeline, "NewRegistrar");
    pipeline.run().waitUntilFinish();
    validateHistoryEntry(DomainHistory.class, domain);
    validateHistoryEntry(ContactHistory.class, contact);
    validateHistoryEntry(HostHistory.class, host);
  }

  private static <T extends EppResource> void validateHistoryEntry(
      Class<? extends HistoryEntry> historyClazz, T resource) {
    ImmutableList<? extends HistoryEntry> historyEntries =
        jpaTm().transact(() -> jpaTm().loadAllOf(historyClazz));
    assertThat(historyEntries.size()).isEqualTo(1);
    HistoryEntry historyEntry = historyEntries.get(0);
    assertThat(historyEntry.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    assertThat(historyEntry.getRegistrarId()).isEqualTo("NewRegistrar");
    EppResource embeddedResource;
    if (historyEntry instanceof DomainHistory) {
      embeddedResource = ((DomainHistory) historyEntry).getDomainContent().get();
    } else if (historyEntry instanceof ContactHistory) {
      embeddedResource = ((ContactHistory) historyEntry).getContactBase().get();
    } else {
      embeddedResource = ((HostHistory) historyEntry).getHostBase().get();
    }
    assertAboutImmutableObjects().that(embeddedResource).hasFieldsEqualTo(resource);
  }
}
