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
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newRegistry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.backup.CommitLogImports;
import google.registry.backup.VersionedEntity;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.persistence.VKey;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.tools.LevelDbLogReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.beam.sdk.values.KV;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BackupTestStore}. */
public class BackupTestStoreTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  @TempDir File tempDir;

  @RegisterExtension InjectExtension injectRule = new InjectExtension();

  private FakeClock fakeClock;
  private BackupTestStore store;

  // Test data:
  private Registry registry;
  private ContactResource contact;
  private DomainBase domain;

  @BeforeEach
  void beforeEach() throws Exception {
    fakeClock = new FakeClock(START_TIME);
    store = new BackupTestStore(fakeClock);
    injectRule.setStaticField(Ofy.class, "clock", fakeClock);

    registry = newRegistry("tld1", "TLD1");
    store.insertOrUpdate(registry);

    contact = newContactResource("contact_1");
    domain = newDomainBase("domain1.tld1", contact);
    store.insertOrUpdate(contact, domain);

    // Save persisted data for assertions.
    registry = (Registry) store.loadAsOfyEntity(registry);
    contact = (ContactResource) store.loadAsOfyEntity(contact);
    domain = (DomainBase) store.loadAsOfyEntity(domain);
  }

  @AfterEach
  void afterEach() throws Exception {
    store.close();
  }

  @Test
  void export_filesCreated() throws IOException {
    String exportRootPath = tempDir.getAbsolutePath();
    assertThat(fakeClock.nowUtc().toString()).isEqualTo("2000-01-01T00:00:00.002Z");
    File exportFolder = new File(exportRootPath, "2000-01-01T00:00:00_002");
    assertWithMessage("Directory %s should not exist.", exportFolder.getAbsoluteFile())
        .that(exportFolder.exists())
        .isFalse();
    File actualExportFolder = export(exportRootPath, Collections.EMPTY_SET);
    assertThat(actualExportFolder).isEquivalentAccordingToCompareTo(exportFolder);
    try (Stream<String> files =
        Files.walk(exportFolder.toPath())
            .filter(Files::isRegularFile)
            .map(Path::toString)
            .map(string -> string.substring(exportFolder.getAbsolutePath().length()))) {
      assertThat(files)
          .containsExactly(
              "/all_namespaces/kind_Registry/input-0",
              "/all_namespaces/kind_DomainBase/input-0",
              "/all_namespaces/kind_ContactResource/input-0");
    }
  }

  @Test
  void export_folderNameChangesWithTime() throws IOException {
    String exportRootPath = tempDir.getAbsolutePath();
    fakeClock.advanceOneMilli();
    File exportFolder = new File(exportRootPath, "2000-01-01T00:00:00_003");
    assertWithMessage("Directory %s should not exist.", exportFolder.getAbsoluteFile())
        .that(exportFolder.exists())
        .isFalse();
    assertThat(export(exportRootPath, Collections.EMPTY_SET))
        .isEquivalentAccordingToCompareTo(exportFolder);
  }

  @Test
  void export_dataReadBack() throws IOException {
    String exportRootPath = tempDir.getAbsolutePath();
    File exportFolder = export(exportRootPath, Collections.EMPTY_SET);
    ImmutableList<Object> loadedRegistries =
        loadExportedEntities(new File(exportFolder, "/all_namespaces/kind_Registry/input-0"));
    assertThat(loadedRegistries).containsExactly(registry);

    ImmutableList<Object> loadedDomains =
        loadExportedEntities(new File(exportFolder, "/all_namespaces/kind_DomainBase/input-0"));
    assertThat(loadedDomains).containsExactly(domain);

    ImmutableList<Object> loadedContacts =
        loadExportedEntities(
            new File(exportFolder, "/all_namespaces/kind_ContactResource/input-0"));
    assertThat(loadedContacts).containsExactly(contact);
  }

  @Test
  void export_excludeSomeEntity() throws IOException {
    Registry newRegistry = newRegistry("tld2", "TLD2");
    store.insertOrUpdate(newRegistry);
    newRegistry = (Registry) store.loadAsOfyEntity(newRegistry);

    String exportRootPath = tempDir.getAbsolutePath();
    File exportFolder =
        export(
            exportRootPath, ImmutableSet.of(Key.create(getCrossTldKey(), Registry.class, "tld1")));
    ImmutableList<Object> loadedRegistries =
        loadExportedEntities(new File(exportFolder, "/all_namespaces/kind_Registry/input-0"));
    assertThat(loadedRegistries).containsExactly(newRegistry);
  }

  @Test
  void saveCommitLogs_fileCreated() {
    File commitLogFile = store.saveCommitLogs(tempDir.getAbsolutePath());
    assertThat(commitLogFile.exists()).isTrue();
    assertThat(commitLogFile.getName()).isEqualTo("commit_diff_until_2000-01-01T00:00:00.002Z");
  }

  @Test
  void saveCommitLogs_inserts() {
    File commitLogFile = store.saveCommitLogs(tempDir.getAbsolutePath());
    assertThat(commitLogFile.exists()).isTrue();
    ImmutableList<VersionedEntity> mutations = CommitLogImports.loadEntities(commitLogFile);
    InitSqlTestUtils.assertContainsExactlyElementsIn(
        mutations,
        KV.of(fakeClock.nowUtc().getMillis() - 2, store.loadAsDatastoreEntity(registry)),
        KV.of(fakeClock.nowUtc().getMillis() - 1, store.loadAsDatastoreEntity(contact)),
        KV.of(fakeClock.nowUtc().getMillis() - 1, store.loadAsDatastoreEntity(domain)));
  }

  @Test
  void saveCommitLogs_deletes() {
    fakeClock.advanceOneMilli();
    store.saveCommitLogs(tempDir.getAbsolutePath());
    ContactResource newContact = newContactResource("contact2");
    VKey<ContactResource> vKey = newContact.createVKey();
    domain =
        domain
            .asBuilder()
            .setRegistrant(vKey)
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(DesignatedContact.Type.ADMIN, vKey),
                    DesignatedContact.create(DesignatedContact.Type.TECH, vKey)))
            .build();
    store.insertOrUpdate(domain, newContact);
    store.delete(contact);
    File commitLogFile = store.saveCommitLogs(tempDir.getAbsolutePath());
    ImmutableList<VersionedEntity> mutations = CommitLogImports.loadEntities(commitLogFile);
    InitSqlTestUtils.assertContainsExactlyElementsIn(
        mutations,
        KV.of(fakeClock.nowUtc().getMillis() - 1, Key.create(contact).getRaw()),
        KV.of(fakeClock.nowUtc().getMillis() - 2, store.loadAsDatastoreEntity(domain)),
        KV.of(fakeClock.nowUtc().getMillis() - 2, store.loadAsDatastoreEntity(newContact)));
  }

  @Test
  void saveCommitLogs_empty() {
    fakeClock.advanceOneMilli();
    store.saveCommitLogs(tempDir.getAbsolutePath());
    fakeClock.advanceOneMilli();
    File commitLogFile = store.saveCommitLogs(tempDir.getAbsolutePath());
    assertThat(commitLogFile.exists()).isTrue();
    assertThat(CommitLogImports.loadEntities(commitLogFile)).isEmpty();
  }

  private File export(String exportRootPath, Set<Key<?>> excludes) throws IOException {
    return store.export(
        exportRootPath,
        ImmutableList.of(ContactResource.class, DomainBase.class, Registry.class),
        excludes);
  }

  private static ImmutableList<Object> loadExportedEntities(File dataFile) throws IOException {
    return Streams.stream(LevelDbLogReader.from(dataFile.toPath()))
        .map(InitSqlTestUtils::bytesToEntity)
        .map(InitSqlTestUtils::datastoreToOfyEntity)
        .collect(ImmutableList.toImmutableList());
  }
}
