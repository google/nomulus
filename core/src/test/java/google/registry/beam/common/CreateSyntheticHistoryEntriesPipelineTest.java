package google.registry.beam.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.removeTmOverrideForTest;
import static google.registry.persistence.transaction.TransactionManagerFactory.setTmOverrideForTest;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;

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
    domain = persistActiveDomain("example.tld");
    contact = jpaTm().transact(() -> jpaTm().loadByKey(domain.getRegistrant()));
    host = persistActiveHost("external.com");
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
    assertAboutImmutableObjects()
        .that(historyEntry.getResourceAtPointInTime().get())
        .isEqualExceptFields(resource, "updateTimestamp");
  }
}
