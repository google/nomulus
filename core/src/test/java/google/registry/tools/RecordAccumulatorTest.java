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
public class RecordAccumulatorTest {

  private static final int BASE_ID = 1001;

  @Rule public final TemporaryFolder tempFs = new TemporaryFolder();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  public void testReadDirectory() throws IOException {
    File subdir = tempFs.newFolder("folder");
    LevelDbFileBuilder builder = new LevelDbFileBuilder(new File(subdir, "data1"));

    // Note that we need to specify property values as "Long" for property comparisons to work
    // correctly because that's how they are deserialized from protos.
    ComparableEntity e1 =
        builder.addEntityProto(
            BASE_ID,
            Property.create("eeny", 100L),
            Property.create("meeny", 200L),
            Property.create("miney", 300L));
    ComparableEntity e2 =
        builder.addEntityProto(
            BASE_ID + 1,
            Property.create("eeny", 100L),
            Property.create("meeny", 200L),
            Property.create("miney", 300L));
    builder.build();

    builder = new LevelDbFileBuilder(new File(subdir, "data2"));

    // Duplicate of the record in the other file.
    builder.addEntityProto(
        BASE_ID,
        Property.create("eeny", 100L),
        Property.create("meeny", 200L),
        Property.create("miney", 300L));

    ComparableEntity e3 =
        builder.addEntityProto(
            BASE_ID + 2,
            Property.create("moxy", 100L),
            Property.create("fruvis", 200L),
            Property.create("cortex", 300L));
    builder.build();

    ImmutableSet<ComparableEntity> entities =
        new RecordAccumulator().readDirectory(subdir, any -> true).getComparableEntitySet();
    assertThat(entities).containsExactly(e1, e2, e3);
  }
}
