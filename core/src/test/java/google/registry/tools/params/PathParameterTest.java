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

package google.registry.tools.params;

import static com.google.common.truth.Truth.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.beust.jcommander.ParameterException;
import java.io.File;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PathParameter}. */
@RunWith(JUnit4.class)
public class PathParameterTest {
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  // ================================ Test Convert ==============================================

  private final PathParameter vanilla = new PathParameter();

  @Test
  public void testConvert_etcPasswd_returnsPath() {
    assertThat((Object) vanilla.convert("/etc/passwd")).isEqualTo(Paths.get("/etc/passwd"));
  }

  @Test
  public void testConvert_null_throws() {
    assertThrows(NullPointerException.class, () -> vanilla.convert(null));
  }

  @Test
  public void testConvert_empty_throws() {
    assertThrows(IllegalArgumentException.class, () -> vanilla.convert(""));
  }

  @Test
  public void testConvert_relativePath_returnsOriginalFile() throws Exception {
    Path currentDirectory = Paths.get("").toAbsolutePath();
    Path file = Paths.get(folder.newFile().toString());
    Path relative = file.relativize(currentDirectory);
    assumeThat(relative, is(not(equalTo(file))));
    assumeThat(relative.toString(), startsWith("../"));
    Path converted = vanilla.convert(file.toString());
    assertThat((Object) converted).isEqualTo(file);
  }

  @Test
  public void testConvert_extraSlash_returnsWithoutSlash() throws Exception {
    Path file = Paths.get(folder.newFile().toString());
    assertThat((Object) vanilla.convert(file + "/")).isEqualTo(file);
  }

  @Test
  public void testConvert_uriNotProvided() {
    assertThrows(FileSystemNotFoundException.class, () -> vanilla.convert("bog://bucket/lolcat"));
  }

  // =========================== Test InputFile Validate ========================================

  private final PathParameter inputFile = new PathParameter.InputFile();

  @Test
  public void testInputFileValidate_normalFile_works() throws Exception {
    inputFile.validate("input", folder.newFile().toString());
  }

  @Test
  public void testInputFileValidate_missingFile_throws() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> inputFile.validate("input", new File(folder.getRoot(), "foo").toString()));
    assertThat(thrown).hasMessageThat().contains("not found");
  }

  @Test
  public void testInputFileValidate_directory_throws() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> inputFile.validate("input", folder.getRoot().toString()));
    assertThat(thrown).hasMessageThat().contains("is a directory");
  }

  @Test
  public void testInputFileValidate_unreadableFile_throws() throws Exception {
    Path file = Paths.get(folder.newFile().toString());
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> inputFile.validate("input", file.toString()));
    assertThat(thrown).hasMessageThat().contains("not readable");
  }

  // =========================== Test OutputFile Validate ========================================

  private final PathParameter outputFile = new PathParameter.OutputFile();

  @Test
  public void testOutputFileValidate_normalFile_works() throws Exception {
    outputFile.validate("input", folder.newFile().toString());
  }

  @Test
  public void testInputFileValidate_characterDeviceBehindSymbolicLinks_works() {
    assumeTrue(Files.exists(Paths.get("/dev/stdin")));
    outputFile.validate("input", "/dev/stdin");
  }

  @Test
  public void testOutputFileValidate_missingFile_works() {
    outputFile.validate("input", new File(folder.getRoot(), "foo").toString());
  }

  @Test
  public void testOutputFileValidate_directory_throws() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> outputFile.validate("input", folder.getRoot().toString()));
    assertThat(thrown).hasMessageThat().contains("is a directory");
  }

  @Test
  public void testOutputFileValidate_notWritable_throws() throws Exception {
    Path file = Paths.get(folder.newFile().toString());
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> outputFile.validate("input", file.toString()));
    assertThat(thrown).hasMessageThat().contains("not writable");
  }

  @Test
  public void testOutputFileValidate_parentDirMissing_throws() {
    Path file = Paths.get(folder.getRoot().toString(), "MISSINGNO", "foo.txt");
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> outputFile.validate("input", file.toString()));
    assertThat(thrown).hasMessageThat().contains("parent dir doesn't exist");
  }

  @Test
  public void testOutputFileValidate_parentDirIsFile_throws() throws Exception {
    Path file = Paths.get(folder.newFile().toString(), "foo.txt");
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> outputFile.validate("input", file.toString()));
    assertThat(thrown).hasMessageThat().contains("parent is non-directory");
  }
}
