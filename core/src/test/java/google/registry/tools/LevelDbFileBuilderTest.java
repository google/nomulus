// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableList;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.model.contact.ContactResource;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.EntityWrapper.Property;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link LevelDbFileBuilder}. */
public class LevelDbFileBuilderTest {

  private static final int BASE_ID = 1001;

  @TempDir Path tmpDir;

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testSingleRecordWrites() throws IOException {
    File logFile = tmpDir.resolve("testfile").toFile();
    LevelDbFileBuilder builder = new LevelDbFileBuilder(logFile);
    EntityWrapper entity =
        EntityWrapper.from(
            BASE_ID, Property.create("first", 100L), Property.create("second", 200L));
    builder.addEntity(entity.getEntity());
    builder.build();

    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(logFile.getPath()));
    assertThat(records).hasSize(1);

    // Reconstitute an entity, make sure that what we've got is the same as what we started with.
    Entity materializedEntity = rawRecordToEntity(records.get(0));
    assertThat(new EntityWrapper(materializedEntity)).isEqualTo(entity);
  }

  @Test
  void testMultipleRecordWrites() throws IOException {
    File logFile = tmpDir.resolve("testfile").toFile();
    LevelDbFileBuilder builder = new LevelDbFileBuilder(logFile);

    // Generate enough records to cross a block boundary.  These records end up being around 80
    // bytes, so 1000 works.
    ImmutableList.Builder<EntityWrapper> originalEntitiesBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 1000; ++i) {
      EntityWrapper entity =
          EntityWrapper.from(
              BASE_ID + i, Property.create("first", 100L), Property.create("second", 200L));
      builder.addEntity(entity.getEntity());
      originalEntitiesBuilder.add(entity);
    }
    builder.build();
    ImmutableList<EntityWrapper> originalEntities = originalEntitiesBuilder.build();

    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(logFile.getPath()));
    assertThat(records).hasSize(1000);
    int index = 0;
    for (byte[] record : records) {
      Entity materializedEntity = rawRecordToEntity(record);
      assertThat(new EntityWrapper(materializedEntity)).isEqualTo(originalEntities.get(index));
      ++index;
    }
  }

  @Test
  void testOfyEntityWrite() throws Exception {
    File logFile = tmpDir.resolve("testfile").toFile();
    LevelDbFileBuilder builder = new LevelDbFileBuilder(logFile);

    ContactResource contact = DatabaseHelper.newContactResource("contact");
    builder.addEntity(tm().transact(() -> auditedOfy().save().toEntity(contact)));
    builder.build();

    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(logFile.getPath()));
    assertThat(records).hasSize(1);
    ContactResource ofyEntity = rawRecordToOfyEntity(records.get(0), ContactResource.class);
    assertThat(ofyEntity.getContactId()).isEqualTo(contact.getContactId());
  }

  private static Entity rawRecordToEntity(byte[] record) {
    EntityProto proto = new EntityProto();
    proto.parseFrom(record);
    return EntityTranslator.createFromPb(proto);
  }

  private static <T> T rawRecordToOfyEntity(byte[] record, Class<T> expectedType) {
    return expectedType.cast(auditedOfy().load().fromEntity(rawRecordToEntity(record)));
  }
}
