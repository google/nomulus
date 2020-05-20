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

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableList;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.testing.AppEngineRule;
import google.registry.tools.LevelDbFileBuilder.Property;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LevelDbFileBuilderTest {

  public static final int BASE_ID = 1001;

  @Rule public final TemporaryFolder tempFs = new TemporaryFolder();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  public void testSingleRecordWrites() throws IOException {
    File subdir = tempFs.newFolder("folder");
    File logFile = new File(subdir, "testfile");
    LevelDbFileBuilder builder = new LevelDbFileBuilder(logFile);
    ComparableEntity entity =
        builder.addEntityProto(
            BASE_ID, Property.create("first", 100L), Property.create("second", 200L));
    builder.build();

    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(logFile.getPath()));
    assertThat(records).hasSize(1);

    // Reconstitute an entity, make sure that what we've got is the same as what we started with.
    EntityProto proto = new EntityProto();
    proto.parseFrom(records.get(0));
    Entity materializedEntity = EntityTranslator.createFromPb(proto);
    assertThat(new ComparableEntity(materializedEntity)).isEqualTo(entity);
  }

  @Test
  public void testMultipleRecordWrites() throws IOException {
    File subdir = tempFs.newFolder("folder");
    File logFile = new File(subdir, "testfile");
    LevelDbFileBuilder builder = new LevelDbFileBuilder(logFile);

    // Generate enough records to cross a block boundary.  These records end up being around 80
    // bytes, so 1000 works.
    ImmutableList.Builder<ComparableEntity> originalEntitiesBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 1000; ++i) {
      ComparableEntity entity =
          builder.addEntityProto(
              BASE_ID + i, Property.create("first", 100L), Property.create("second", 200L));
      originalEntitiesBuilder.add(entity);
    }
    builder.build();
    ImmutableList<ComparableEntity> originalEntities = originalEntitiesBuilder.build();

    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(logFile.getPath()));
    assertThat(records).hasSize(1000);
    int index = 0;
    for (byte[] record : records) {
      EntityProto proto = new EntityProto();
      proto.parseFrom(record);
      Entity materializedEntity = EntityTranslator.createFromPb(proto);
      assertThat(new ComparableEntity(materializedEntity)).isEqualTo(originalEntities.get(index));
      ++index;
    }
  }
}
