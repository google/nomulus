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
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link GcsPluginUtilsTest} */
final class GcsPluginUtilsTest {

  private static final Joiner filenameJoiner = Joiner.on(File.separator);

  @SuppressWarnings("WeakerAccess")
  @TempDir
  Path tmpDir;

  @Test
  void testGetContentType_knownTypes() {
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
  void testGetContentType_unknownTypes() {
    assertThat(getContentType("path/to/file.unknown")).isEqualTo("application/octet-stream");
  }

  @Test
  void testUploadFileToGcs() {
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
  void testUploadFilesToGcsMultithread() {
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
  void testToByteArraySupplier_string() {
    assertThat(toByteArraySupplier("my string").get()).isEqualTo("my string".getBytes(UTF_8));
  }

  @Test
  void testToByteArraySupplier_stringSupplier() {
    assertThat(toByteArraySupplier(() -> "my string").get()).isEqualTo("my string".getBytes(UTF_8));
  }

  @Test
  void testToByteArraySupplier_file() throws Exception {
    Path dir = createDirectory(tmpDir.resolve("arbitrary"));
    Path file = createFile(dir.resolve("file.txt"));
    Files.write(file, "some data".getBytes(UTF_8));
    assertThat(toByteArraySupplier(file.toFile()).get()).isEqualTo("some data".getBytes(UTF_8));
  }

  private ImmutableMap<String, String> readAllFiles(FilesWithEntryPoint reportFiles) {
    return reportFiles.files().entrySet().stream()
        .collect(
            toImmutableMap(
                entry -> entry.getKey().toString(),
                entry -> new String(entry.getValue().get(), UTF_8)));
  }

  @Test
  void testCreateReportFiles_destinationIsFile() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path somePath = createDirectories(root.resolve("some/path"));
    Path destination = createFile(somePath.resolve("file.txt"));
    Files.write(destination, "some data".getBytes(UTF_8));
    // Since the entry point is obvious here - any hint given is just ignored.
    File ignoredHint = createFile(root.resolve("ignored.txt")).toFile();

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "file.txt"));
    assertThat(readAllFiles(files))
        .containsExactly(filenameJoiner.join("some", "path", "file.txt"), "some data");
  }

  @Test
  void testCreateReportFiles_destinationDoesntExist() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    File destination = root.resolve("non/existing.txt").toFile();
    assertThat(destination.isFile()).isFalse();
    assertThat(destination.isDirectory()).isFalse();
    // Since there are no files, any hint given is obviously wrong and will be ignored.
    File ignoredHint = createFile(root.resolve("ignored.txt")).toFile();

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination, Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString()).isEqualTo(filenameJoiner.join("non", "existing.txt"));
    assertThat(files.files()).isEmpty();
  }

  @Test
  void testCreateReportFiles_noFiles() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path destination = createDirectories(root.resolve("some/path"));
    createDirectories(destination.resolve("a/b"));
    createDirectory(destination.resolve("c"));
    // Since there are not files, any hint given is obviously wrong and will be ignored.
    File ignoredHint = createFile(root.resolve("ignored.txt")).toFile();

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.of(ignoredHint), root);

    assertThat(files.entryPoint().toString()).isEqualTo(filenameJoiner.join("some", "path"));
    assertThat(files.files()).isEmpty();
  }

  @Test
  void testCreateReportFiles_oneFile() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path destination = createDirectories(root.resolve("some/path"));
    createDirectories(destination.resolve("a/b"));
    createDirectory(destination.resolve("c"));
    Files.write(createFile(destination.resolve("a/file.txt")), "some data".getBytes(UTF_8));
    // Since the entry point is obvious here - any hint given is just ignored.
    File ignoredHint = createFile(root.resolve("ignored.txt")).toFile();

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.of(ignoredHint), root);

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
  void testCreateReportFiles_multipleFiles_noHint() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path destination = createDirectories(root.resolve("some/path"));
    createDirectories(destination.resolve("a/b"));
    createDirectory(destination.resolve("c"));

    Files.write(createFile(destination.resolve("index.html")), "some data".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("a/index.html")), "wrong index".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("c/style.css")), "css file".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("my_image.png")), "images".getBytes(UTF_8));

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.empty(), root);

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
  void testCreateReportFiles_multipleFiles_withBadHint() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path destination = createDirectories(root.resolve("some/path"));
    // This entry point points to a directory, which isn't an appropriate entry point
    File badEntryPoint = createDirectories(destination.resolve("a/b")).toFile();
    createDirectory(destination.resolve("c"));

    Files.write(createFile(destination.resolve("index.html")), "some data".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("a/index.html")), "wrong index".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("c/style.css")), "css file".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("my_image.png")), "images".getBytes(UTF_8));

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.of(badEntryPoint), root);

    assertThat(files.entryPoint().toString())
        .isEqualTo(filenameJoiner.join("some", "path", "path.zip"));
    assertThat(readAllFiles(files).keySet())
        .containsExactly(filenameJoiner.join("some", "path", "path.zip"));
  }

  @Test
  void testCreateReportFiles_multipleFiles_withGoodHint() throws Exception {
    Path root = toNormalizedPath(createDirectories(tmpDir.resolve("my/root")).toAbsolutePath());
    Path destination = createDirectories(root.resolve("some/path"));
    createDirectories(destination.resolve("a/b"));
    createDirectory(destination.resolve("c"));
    // The hint is an actual file nested in the destination directory!
    Path goodEntryPoint = createFile(destination.resolve("index.html"));

    Files.write(goodEntryPoint, "some data".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("a/index.html")), "wrong index".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("c/style.css")), "css file".getBytes(UTF_8));
    Files.write(createFile(destination.resolve("my_image.png")), "images".getBytes(UTF_8));

    FilesWithEntryPoint files =
        readFilesWithEntryPoint(destination.toFile(), Optional.of(goodEntryPoint.toFile()), root);

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
