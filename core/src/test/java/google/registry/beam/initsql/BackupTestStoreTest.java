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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newRegistry;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.testing.FakeClock;
import google.registry.tools.LevelDbLogReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BackupTestStore}. */
public class BackupTestStoreTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private FakeClock fakeClock;
  private BackupTestStore store;

  @TempDir File tempDir;

  @BeforeEach
  void beforeEach() throws Exception {
    fakeClock = new FakeClock(START_TIME);
    store = new BackupTestStore(fakeClock);

    store.insertOrUpdate(newRegistry("tld1", "TLD1"));
    ContactResource contact1 = newContactResource("contact_1");
    DomainBase domain1 = newDomainBase("domain1.tld1", contact1);
    store.insertOrUpdate(contact1, domain1);
  }

  @AfterEach
  void afterEach() throws Exception {
    store.close();
  }

  @Test
  void export_filesCreated() throws IOException {
    String exportRootPath = tempDir.getAbsolutePath();
    File exportFolder = new File(exportRootPath, "2000-01-01T00:00:00_000");
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
    File exportFolder = new File(exportRootPath, "2000-01-01T00:00:00_001");
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
    ImmutableList<String> tldStrings =
        loadPropertyFromExportedEntities(
            new File(exportFolder, "/all_namespaces/kind_Registry/input-0"),
            Registry.class,
            Registry::getTldStr);
    assertThat(tldStrings).containsExactly("tld1");
    ImmutableList<String> domainStrings =
        loadPropertyFromExportedEntities(
            new File(exportFolder, "/all_namespaces/kind_DomainBase/input-0"),
            DomainBase.class,
            DomainBase::getFullyQualifiedDomainName);
    assertThat(domainStrings).containsExactly("domain1.tld1");
    ImmutableList<String> contactIds =
        loadPropertyFromExportedEntities(
            new File(exportFolder, "/all_namespaces/kind_ContactResource/input-0"),
            ContactResource.class,
            ContactResource::getContactId);
    assertThat(contactIds).containsExactly("contact_1");
  }

  @Test
  void export_excludeSomeEntity() throws IOException {
    store.insertOrUpdate(newRegistry("tld2", "TLD2"));
    String exportRootPath = tempDir.getAbsolutePath();
    File exportFolder =
        export(
            exportRootPath, ImmutableSet.of(Key.create(getCrossTldKey(), Registry.class, "tld1")));
    ImmutableList<String> tlds =
        loadPropertyFromExportedEntities(
            new File(exportFolder, "/all_namespaces/kind_Registry/input-0"),
            Registry.class,
            Registry::getTldStr);
    assertThat(tlds).containsExactly("tld2");
  }

  private File export(String exportRootPath, Set<Key<?>> excludes) throws IOException {
    return store.export(
        exportRootPath,
        ImmutableList.of(ContactResource.class, DomainBase.class, Registry.class),
        excludes);
  }

  private static <T> ImmutableList<String> loadPropertyFromExportedEntities(
      File dataFile, Class<T> ofyEntityType, Function<T, String> getter) throws IOException {
    return Streams.stream(LevelDbLogReader.from(dataFile.toPath()))
        .map(bytes -> toOfyEntity(bytes, ofyEntityType))
        .map(getter)
        .collect(ImmutableList.toImmutableList());
  }

  private static <T> T toOfyEntity(byte[] rawRecord, Class<T> ofyEntityType) {
    EntityProto proto = new EntityProto();
    proto.parseFrom(rawRecord);
    Entity entity = EntityTranslator.createFromPb(proto);
    return ofyEntityType.cast(ofy().load().fromEntity(entity));
  }
}
