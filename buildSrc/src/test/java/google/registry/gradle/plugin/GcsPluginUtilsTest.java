// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.gradle.plugin;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.gradle.plugin.GcsPluginUtils.getContentType;
import static google.registry.gradle.plugin.GcsPluginUtils.readFilesWithEntryPoint;
import static google.registry.gradle.plugin.GcsPluginUtils.toByteArraySupplier;
import static google.registry.gradle.plugin.GcsPluginUtils.toNormalizedPath;
import static google.registry.gradle.plugin.GcsPluginUtils.uploadFileToGcs;
import static google.registry.gradle.plugin.GcsPluginUtils.uploadFilesToGcsMultithread;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GcsPluginUtilsTest} */
@RunWith(JUnit4.class)
public final class GcsPluginUtilsTest {

  private static final Joiner filenameJoiner = Joiner.on(File.separator);

  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void testGetContentType_knownTypes() {
    assertThat(getContentType("path/to/file.html")).isEqualTo("text/html");
    assertThat(getContentType("path/to/file.htm")).isEqualTo("text/html");
    assertThat(getContentType("path/to/file.log")).isEqualTo("text/plain");
    assertThat(getContentType("path/to/file.txt")).isEqualTo("text/plain");
    assertThat(getContentType("path/to/file.css")).isEqualTo("text/css");
    assertThat(getContentType("path/to/file.xml")).isEqualTo("text/xml");
    assertThat(getContentType("path/to/file.zip")).isEqualTo("application/zip");
    assertThat(getContentType("path/to/file.js")).isEqualTo("text/javascript");
  }

  @Test
  public void testGetContentType_unknownTypes() {
    assertThat(getContentType("path/to/file.unknown")).isEqualTo("application/octet-stream");
  }

  @Test
  public void testUploadFileToGcs() {
    Storage storage = mock(Storage.class);
    uploadFileToGcs(
        storage, "my-bucket", Paths.get("my", "filename.txt"), toByteArraySupplier("my data"));
    verify(storage)
        .create(
            BlobInfo.newBuilder("my-bucket", "my/filename.txt")
                .setContentType("text/plain")
                .build(),
            "my data".getBytes(UTF_8));
    verifyNoMoreInteractions(storage);
  }

  @Test
  public void testUploadFilesToGcsMultithread() {
    Storage storage = mock(Storage.class);
    uploadFilesToGcsMultithread(
        storage,
        "my-bucket",
        Paths.get("my", "folder"),
        ImmutableMap.of(
            Paths.get("some", "index.html"), toByteArraySupplier("some web page"),
            Paths.get("some", "style.css"), toByteArraySupplier("some style"),
            Paths.get("other", "index.html"), toByteArraySupplier("other web page"),
            Paths.get("other", "style.css"), toByteArraySupplier("other style")));
    verify(storage)
        .create(
            BlobInfo.newBuilder("my-bucket", "my/folder/some/index.html")
                .setContentType("text/html")
                .build(),
            "some web page".getBytes(UTF_8));
    verify(storage)
        .create(
            BlobInfo.newBuilder("my-bucket", "my/folder/some/style.css")
                .setContentType("text/css")
                .build(),
            "some style".getBytes(UTF_8));
    verify(storage)
        .create(
            BlobInfo.newBuilder("my-bucket", "my/folder/other/index.html")
                .setContentType("text/html")
                .build(),
            "other web page".getBytes(UTF_8));
    verify(storage)
        .create(
            BlobInfo.newBuilder("my-bucket", "my/folder/other/style.css")
                .setContentType("text/css")
                .build(),
            "other style".getBytes(UTF_8));
    verifyNoMoreInteractions(storage);
  }

  @Test
  public void testToByteArraySupplier_string() {
    assertThat(toByteArraySupplier("my string").get()).isEqualTo("my string".getBytes(UTF_8));
  }

  @Test
  public void testToByteArraySupplier_stringSupplier() {
    assertThat(toByteArraySupplier(() -> "my string").get()).isEqualTo("my string".getBytes(UTF_8));
  }

  @Test
  public void testToByteArraySupplier_file() throws Exception {
    folder.newFolder("arbitrary");
    File file = folder.newFile("arbitrary/file.txt");
    Files.write(file.toPath(), "some data".getBytes(UTF_8));
    assertThat(toByteArraySupplier(file).get()).isEqualTo("some data".getBytes(UTF_8));
  }

  private ImmutableMap<String, String> readAllFiles(FilesWithEntryPoint reportFiles) {
    return reportFiles.files().entrySet().stream()
        .collect(
            toImmutableMap(
                entry -> entry.getKey().toString(),
                entry -> new String(entry.getValue().get(), UTF_8)));
  }

  @Test
  public void testCreateReportFiles_destinationIsFile() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    folder.newFolder("my", "root", "some", "path");
    File destination = folder.newFile("my/root/some/path/file.txt");
    Files.write(destination.toPath(), "some data".getBytes(UTF_8));
    // Since the entry point is obvious here - any hint given is just ignored.
    File ignoredHint = folder.newFile("my/root/ignored.txt");

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "file.txt"));
    assertThat(readAllFiles(files))
        .containsExactly(filenameJoiner.join("some", "path", "file.txt"), "some data");
  }

  @Test
  public void testCreateReportFiles_destinationDoesntExist() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = root.resolve("non/existing.txt").toFile();
    assertThat(destination.isFile()).isFalse();
    assertThat(destination.isDirectory()).isFalse();
    // Since there are not files, any hint given is obvioulsy wrong and will be ignored.
    File ignoredHint = folder.newFile("my/root/ignored.txt");

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString()).isEqualTo(filenameJoiner.join("non", "existing.txt"));
    assertThat(files.files()).isEmpty();
  }

  @Test
  public void testCreateReportFiles_noFiles() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = folder.newFolder("my", "root", "some", "path");
    folder.newFolder("my", "root", "some", "path", "a", "b");
    folder.newFolder("my", "root", "some", "path", "c");
    // Since there are not files, any hint given is obvioulsy wrong and will be ignored.
    File ignoredHint = folder.newFile("my/root/ignored.txt");

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString()).isEqualTo(filenameJoiner.join("some", "path"));
    assertThat(files.files()).isEmpty();
  }

  @Test
  public void testCreateReportFiles_oneFile() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = folder.newFolder("my", "root", "some", "path");
    folder.newFolder("my", "root", "some", "path", "a", "b");
    folder.newFolder("my", "root", "some", "path", "c");
    Files.write(
        folder.newFile("my/root/some/path/a/file.txt").toPath(), "some data".getBytes(UTF_8));
    // Since the entry point is obvious here - any hint given is just ignored.
    File ignoredHint = folder.newFile("my/root/ignored.txt");

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "a", "file.txt"));
    assertThat(readAllFiles(files))
        .containsExactly(filenameJoiner.join("some", "path", "a", "file.txt"), "some data");
  }

  /**
   * Currently tests the "unimplemented" behavior.
   *
   * <p>TODO(guyben): switch to checking zip file instead.
   */
  @Test
  public void testCreateReportFiles_multipleFiles_noHint() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = folder.newFolder("my", "root", "some", "path");
    folder.newFolder("my", "root", "some", "path", "a", "b");
    folder.newFolder("my", "root", "some", "path", "c");

    Files.write(
        folder.newFile("my/root/some/path/index.html").toPath(), "some data".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/a/index.html").toPath(), "wrong index".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/c/style.css").toPath(), "css file".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/my_image.png").toPath(), "images".getBytes(UTF_8));

    FilesWithEntryPoint files = readFilesWithEntryPoint(destination, Optional.empty(), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "path.zip"));
    assertThat(readAllFiles(files).keySet())
        .containsExactly(filenameJoiner.join("some", "path", "path.zip"));
  }

  /**
   * Currently tests the "unimplemented" behavior.
   *
   * <p>TODO(guyben): switch to checking zip file instead.
   */
  @Test
  public void testCreateReportFiles_multipleFiles_withBadHint() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = folder.newFolder("my", "root", "some", "path");
    // This entry point points to a directory, which isn't an appropriate entry point
    File badEntryPoint = folder.newFolder("my", "root", "some", "path", "a", "b");
    folder.newFolder("my", "root", "some", "path", "c");

    Files.write(
        folder.newFile("my/root/some/path/index.html").toPath(), "some data".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/a/index.html").toPath(), "wrong index".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/c/style.css").toPath(), "css file".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/my_image.png").toPath(), "images".getBytes(UTF_8));

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(badEntryPoint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "path.zip"));
    assertThat(readAllFiles(files).keySet())
        .containsExactly(filenameJoiner.join("some", "path", "path.zip"));
  }

  @Test
  public void testCreateReportFiles_multipleFiles_withGoodHint() throws Exception {
    Path root = toNormalizedPath(folder.newFolder("my", "root"));
    File destination = folder.newFolder("my", "root", "some", "path");
    folder.newFolder("my", "root", "some", "path", "a", "b");
    folder.newFolder("my", "root", "some", "path", "c");
    // The hint is an actual file nested in the destination directory!
    File goodEntryPoint = folder.newFile("my/root/some/path/index.html");

    Files.write(goodEntryPoint.toPath(), "some data".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/a/index.html").toPath(), "wrong index".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/c/style.css").toPath(), "css file".getBytes(UTF_8));
    Files.write(
        folder.newFile("my/root/some/path/my_image.png").toPath(), "images".getBytes(UTF_8));

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(goodEntryPoint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "index.html"));
    assertThat(readAllFiles(files))
        .containsExactly(
            filenameJoiner.join("some", "path", "index.html"), "some data",
            filenameJoiner.join("some", "path", "a", "index.html"), "wrong index",
            filenameJoiner.join("some", "path", "c", "style.css"), "css file",
            filenameJoiner.join("some", "path", "my_image.png"), "images");
  }
}
