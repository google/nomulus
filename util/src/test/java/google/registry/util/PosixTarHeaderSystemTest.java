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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.SystemInfo.hasCommand;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** System integration tests for {@link PosixTarHeader}. */
@RunWith(JUnit4.class)
public class PosixTarHeaderSystemTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  @Ignore
  public void testCreateSingleFileArchive() throws Exception {
    assumeTrue(hasCommand("tar"));

    // We have some data (in memory) that we'll call hello.txt.
    String fileName = "hello.txt";
    byte[] fileData = "hello world\n".getBytes(UTF_8);

    // We're going to put it in a new tar archive (on the filesystem) named hello.tar.
    String tarName = "hello.tar";
    File tarFile = folder.newFile(tarName);
    try (FileOutputStream output = new FileOutputStream(tarFile)) {
      output.write(new PosixTarHeader.Builder()
          .setName(fileName)
          .setSize(fileData.length)
          .build()
          .getBytes());
      output.write(fileData);
      output.write(new byte[512 - fileData.length % 512]);  // Align with 512-byte block size.
      output.write(new byte[1024]);  // Bunch of null bytes to indicate end of archive.
    }
    assertThat(tarFile.length() % 512).isEqualTo(0);
    assertThat(tarFile.length() / 512).isEqualTo(2 + 2);

    // Now we run the system's tar command to extract our file.
    String[] cmd = {"tar", "-xf", tarName};
    String[] env = {"PATH=" + System.getenv("PATH")};
    File     cwd = folder.getRoot();
    Process  pid = Runtime.getRuntime().exec(cmd, env, cwd);
    String   err = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertThat(pid.waitFor()).isEqualTo(0);
    assertThat(err.trim()).isEmpty();

    // And verify that hello.txt came out.
    File dataFile = new File(cwd, fileName);
    assertThat(dataFile.exists()).isTrue();
    assertThat(dataFile.isFile()).isTrue();
    assertThat(Files.asByteSource(dataFile).read()).isEqualTo(fileData);

    // And that nothing else came out.
    Set<String> expectedFiles = ImmutableSet.of(tarName, fileName);
    assertThat(ImmutableSet.copyOf(folder.getRoot().list())).isEqualTo(expectedFiles);
  }

  @Test
  @Ignore
  public void testCreateMultiFileArchive() throws Exception {
    assumeTrue(hasCommand("tar"));

    Map<String, String> files = ImmutableMap.of(
        "one.txt", ""
            + "There is data on line one\n"
            + "and on line two\n"
            + "and on line three\n",
        "two.txt", ""
            + "There is even more data\n"
            + "in this second file\n"
            + "with its own three lines\n",
        "subdir/three.txt", ""
            + "More data\n"
            + "but only two lines\n");

    String tarName = "hello.tar";
    File tarFile = folder.newFile(tarName);
    try (FileOutputStream output = new FileOutputStream(tarFile)) {
      for (String name : files.keySet()) {
        byte[] data = files.get(name).getBytes(UTF_8);
        output.write(new PosixTarHeader.Builder()
            .setName(name)
            .setSize(data.length)
            .build()
            .getBytes());
        output.write(data);
        output.write(new byte[512 - data.length % 512]);
      }
      output.write(new byte[1024]);
    }
    assertThat(tarFile.length() % 512).isEqualTo(0);
    assertThat(tarFile.length() / 512).isEqualTo(files.size() * 2 + 2);

    String[] cmd = {"tar", "-xf", tarName};
    String[] env = {"PATH=" + System.getenv("PATH")};
    File     cwd = folder.getRoot();
    Process  pid = Runtime.getRuntime().exec(cmd, env, cwd);
    String   err = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertThat(pid.waitFor()).isEqualTo(0);
    assertThat(err.trim()).isEmpty();

    for (String name : files.keySet()) {
      File file = new File(folder.getRoot(), name);
      assertWithMessage(name + " exists").that(file.exists()).isTrue();
      assertWithMessage(name + " is a file").that(file.isFile()).isTrue();
      byte[] data = files.get(name).getBytes(UTF_8);
      assertThat(Files.asByteSource(file).read()).isEqualTo(data);
    }
  }

  @Test
  @Ignore
  public void testReadArchiveUstar() throws Exception {
    assumeTrue(hasCommand("tar"));

    String one = "the first line";
    String two = "the second line";
    File cwd = folder.getRoot();
    Files.write(one.getBytes(UTF_8), new File(cwd, "one"));
    Files.write(two.getBytes(UTF_8), new File(cwd, "two"));

    String[] cmd = {"tar", "--format=ustar", "-cf", "lines.tar", "one", "two"};
    String[] env = {"PATH=" + System.getenv("PATH")};
    Process  pid = Runtime.getRuntime().exec(cmd, env, cwd);
    String   err = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertThat(pid.waitFor()).isEqualTo(0);
    assertThat(err.trim()).isEmpty();

    PosixTarHeader header;
    byte[] block = new byte[512];
    try (FileInputStream input = new FileInputStream(new File(cwd, "lines.tar"))) {
      assertThat(input.read(block)).isEqualTo(512);
      header = PosixTarHeader.from(block);
      assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
      assertThat(header.getName()).isEqualTo("one");
      assertThat(header.getSize()).isEqualTo(one.length());
      assertThat(input.read(block)).isEqualTo(512);
      assertThat(one).isEqualTo(new String(block, 0, one.length(), UTF_8));

      assertThat(input.read(block)).isEqualTo(512);
      header = PosixTarHeader.from(block);
      assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
      assertThat(header.getName()).isEqualTo("two");
      assertThat(header.getSize()).isEqualTo(two.length());
      assertThat(input.read(block)).isEqualTo(512);
      assertThat(two).isEqualTo(new String(block, 0, two.length(), UTF_8));

      assertThat(input.read(block)).isEqualTo(512);
      assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
      assertThat(input.read(block)).isEqualTo(512);
      assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    }
  }

  @Test
  @Ignore
  public void testReadArchiveDefaultFormat() throws Exception {
    assumeTrue(hasCommand("tar"));

    String truth = "No one really knows\n";
    Files.write(truth.getBytes(UTF_8), folder.newFile("truth.txt"));

    String[] cmd = {"tar", "-cf", "steam.tar", "truth.txt"};
    String[] env = {"PATH=" + System.getenv("PATH")};
    File     cwd = folder.getRoot();
    Process  pid = Runtime.getRuntime().exec(cmd, env, cwd);
    String   err = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertThat(pid.waitFor()).isEqualTo(0);
    assertThat(err.trim()).isEmpty();

    PosixTarHeader header;
    byte[] block = new byte[512];
    try (FileInputStream input = new FileInputStream(new File(cwd, "steam.tar"))) {
      assertThat(input.read(block)).isEqualTo(512);
      header = PosixTarHeader.from(block);
      assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
      assertThat(header.getName()).isEqualTo("truth.txt");
      assertThat(header.getSize()).isEqualTo(truth.length());
      assertThat(input.read(block)).isEqualTo(512);
      assertThat(truth).isEqualTo(new String(block, 0, truth.length(), UTF_8));

      assertThat(input.read(block)).isEqualTo(512);
      assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
      assertThat(input.read(block)).isEqualTo(512);
      assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    }
  }

  @Test
  @Ignore
  public void testCreateBigWebScaleData() throws Exception {
    assumeTrue(hasCommand("tar"));

    String name = "rando_numberissian.mov";
    byte[] data = new byte[4 * 1024 * 1024];
    Random rand = new Random();
    rand.nextBytes(data);

    String tarName = "occupy.tar";
    File tarFile = folder.newFile(tarName);
    try (FileOutputStream output = new FileOutputStream(tarFile)) {
      output.write(new PosixTarHeader.Builder()
          .setName(name)
          .setSize(data.length)
          .build()
          .getBytes());
      output.write(data);
      output.write(new byte[1024]);
    }
    assertThat(tarFile.length() % 512).isEqualTo(0);

    String[] cmd = {"tar", "-xf", tarName};
    String[] env = {"PATH=" + System.getenv("PATH")};
    File     cwd = folder.getRoot();
    Process  pid = Runtime.getRuntime().exec(cmd, env, cwd);
    String   err = CharStreams.toString(new InputStreamReader(pid.getErrorStream(), UTF_8));
    assertThat(pid.waitFor()).isEqualTo(0);
    assertThat(err.trim()).isEmpty();

    File dataFile = new File(cwd, name);
    assertThat(dataFile.exists()).isTrue();
    assertThat(dataFile.isFile()).isTrue();
    assertThat(Files.asByteSource(dataFile).read()).isEqualTo(data);

    Set<String> expectedFiles = ImmutableSet.of(tarName, name);
    assertThat(ImmutableSet.copyOf(folder.getRoot().list())).isEqualTo(expectedFiles);
  }
}
