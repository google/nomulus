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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Resources;
import google.registry.testing.AppEngineRule;
import google.registry.tools.LevelDbFileBuilder.Property;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CompareDbBackupsTest {

  private static final int BASE_ID = 1001;

  // Capture standard output.
  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private PrintStream orgStdout;

  @Rule public final TemporaryFolder tempFs = new TemporaryFolder();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void before() {
    orgStdout = System.out;
    System.setOut(new PrintStream(stdout));
  }

  @After
  public void after() {
    System.setOut(orgStdout);
  }

  @Test
  public void testLoadBackup() {
    URL backupRootFolder = Resources.getResource("google/registry/tools/datastore-export");
    CompareDbBackups.main(new String[] {backupRootFolder.getPath(), backupRootFolder.getPath()});
    String output = new String(stdout.toByteArray(), UTF_8);
    assertThat(output).containsMatch("Both sets have the same 41 entities");
  }

  @Test
  public void testCompareBackups() throws Exception {

    // Create two directories corresponding to data dumps.
    File dump1 = tempFs.newFolder("dump1");
    LevelDbFileBuilder builder = new LevelDbFileBuilder(new File(dump1, "output-data1"));
    builder.addEntityProto(
        BASE_ID,
        Property.create("eeny", 100L),
        Property.create("meeny", 200L),
        Property.create("miney", 300L));
    builder.addEntityProto(
        BASE_ID + 1,
        Property.create("moxey", 100L),
        Property.create("minney", 200L),
        Property.create("motz", 300L));
    builder.build();

    File dump2 = tempFs.newFolder("dump2");
    builder = new LevelDbFileBuilder(new File(dump2, "output-data2"));
    builder.addEntityProto(
        BASE_ID + 1,
        Property.create("moxey", 100L),
        Property.create("minney", 200L),
        Property.create("motz", 300L));
    builder.addEntityProto(
        BASE_ID + 2,
        Property.create("blutzy", 100L),
        Property.create("fishey", 200L),
        Property.create("strutz", 300L));
    builder.build();

    CompareDbBackups.main(new String[] {dump1.getCanonicalPath(), dump2.getCanonicalPath()});
    String output = new String(stdout.toByteArray(), UTF_8);
    assertThat(output)
        .containsMatch("(?s)1 records were removed.*eeny.*1 records were added.*blutzy");
  }
}
