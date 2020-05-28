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
import google.registry.testing.DatastoreEntityExtension;
import google.registry.tools.EntityWrapper.Property;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.rules.TemporaryFolder;

public class CompareDbBackupsTest {

  private static final int BASE_ID = 1001;

  // Capture standard output.
  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private PrintStream orgStdout;

  public final TemporaryFolder tempFs = new TemporaryFolder();

  @RegisterExtension
  public DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @BeforeEach
  public void before() throws IOException {
    orgStdout = System.out;
    System.setOut(new PrintStream(stdout));
    tempFs.create();
  }

  @AfterEach
  public void after() {
    System.setOut(orgStdout);
    tempFs.delete();
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
    builder.addEntity(
        EntityWrapper.from(
                BASE_ID,
                Property.create("eeny", 100L),
                Property.create("meeny", 200L),
                Property.create("miney", 300L))
            .getEntity());
    builder.addEntity(
        EntityWrapper.from(
                BASE_ID + 1,
                Property.create("moxey", 100L),
                Property.create("minney", 200L),
                Property.create("motz", 300L))
            .getEntity());
    builder.build();

    File dump2 = tempFs.newFolder("dump2");
    builder = new LevelDbFileBuilder(new File(dump2, "output-data2"));
    builder.addEntity(
        EntityWrapper.from(
                BASE_ID + 1,
                Property.create("moxey", 100L),
                Property.create("minney", 200L),
                Property.create("motz", 300L))
            .getEntity());
    builder.addEntity(
        EntityWrapper.from(
                BASE_ID + 2,
                Property.create("blutzy", 100L),
                Property.create("fishey", 200L),
                Property.create("strutz", 300L))
            .getEntity());
    builder.build();

    CompareDbBackups.main(new String[] {dump1.getCanonicalPath(), dump2.getCanonicalPath()});
    String output = new String(stdout.toByteArray(), UTF_8);
    assertThat(output)
        .containsMatch("(?s)1 records were removed.*eeny.*1 records were added.*blutzy");
  }
}
