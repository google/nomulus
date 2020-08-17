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

import com.google.common.collect.ImmutableSet;
import google.registry.testing.AppEngineExtension;
import google.registry.tools.EntityWrapper.Property;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link RecordAccumulator}. */
public class RecordAccumulatorTest {

  private static final int BASE_ID = 1001;

  @TempDir public File tmpDir;

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testReadDirectory() throws IOException {
    LevelDbFileBuilder builder = new LevelDbFileBuilder(new File(tmpDir, "data1"));

    // Note that we need to specify property values as "Long" for property comparisons to work
    // correctly because that's how they are deserialized from protos.
    EntityWrapper e1 =
        EntityWrapper.from(
            BASE_ID,
            Property.create("eeny", 100L),
            Property.create("meeny", 200L),
            Property.create("miney", 300L));
    builder.addEntity(e1.getEntity());
    EntityWrapper e2 =
        EntityWrapper.from(
            BASE_ID + 1,
            Property.create("eeny", 100L),
            Property.create("meeny", 200L),
            Property.create("miney", 300L));
    builder.addEntity(e2.getEntity());
    builder.build();

    builder = new LevelDbFileBuilder(new File(tmpDir, "data2"));

    // Duplicate of the record in the other file.
    builder.addEntity(
        EntityWrapper.from(
                BASE_ID,
                Property.create("eeny", 100L),
                Property.create("meeny", 200L),
                Property.create("miney", 300L))
            .getEntity());

    EntityWrapper e3 =
        EntityWrapper.from(
            BASE_ID + 2,
            Property.create("moxy", 100L),
            Property.create("fruvis", 200L),
            Property.create("cortex", 300L));
    builder.addEntity(e3.getEntity());
    builder.build();

    ImmutableSet<EntityWrapper> entities =
        RecordAccumulator.readDirectory(tmpDir, any -> true).getEntityWrapperSet();
    assertThat(entities).containsExactly(e1, e2, e3);
  }
}
