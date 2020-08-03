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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.gradle.plugin.GcsPluginUtils.toByteArraySupplier;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.gradle.plugin.ProjectData.TaskData;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Tests for {@link CoverPageGenerator} */
final class CoverPageGeneratorTest {

  private static final ProjectData EMPTY_PROJECT =
      ProjectData.builder()
          .setName("project-name")
          .setDescription("project-description")
          .setGradleVersion("gradle-version")
          .setProjectProperties(ImmutableMap.of("key", "value"))
          .setSystemProperties(ImmutableMap.of())
          .setTasksRequested(ImmutableSet.of(":a:task1", ":a:task2"))
          .build();

  private static final TaskData EMPTY_TASK_SUCCESS =
      TaskData.builder()
          .setUniqueName("task-success")
          .setDescription("a successful task")
          .setState(TaskData.State.SUCCESS)
          .build();

  private static final TaskData EMPTY_TASK_FAILURE =
      TaskData.builder()
          .setUniqueName("task-failure")
          .setDescription("a failed task")
          .setState(TaskData.State.FAILURE)
          .build();

  private static final TaskData EMPTY_TASK_UP_TO_DATE =
      TaskData.builder()
          .setUniqueName("task-up-to-date")
          .setDescription("an up-to-date task")
          .setState(TaskData.State.UP_TO_DATE)
          .build();

  private static final Joiner filenameJoiner = Joiner.on(File.separator);

  private ImmutableMap<String, String> getGeneratedFiles(ProjectData project) {
    CoverPageGenerator coverPageGenerator = new CoverPageGenerator(project);
    FilesWithEntryPoint files = coverPageGenerator.getFilesToUpload();
    return files.files().entrySet().stream()
        .collect(
            toImmutableMap(
                entry -> entry.getKey().toString(),
                entry -> new String(entry.getValue().get(), UTF_8)));
  }

  private String getContentOfGeneratedFile(ProjectData project, String expectedPath) {
    ImmutableMap<String, String> files = getGeneratedFiles(project);
    assertThat(files).containsKey(expectedPath);
    return files.get(expectedPath);
  }

  private String getCoverPage(ProjectData project) {
    return getContentOfGeneratedFile(project, "index.html");
  }

  @Test
  void testGetFilesToUpload_entryPoint_isIndexHtml() {
    CoverPageGenerator coverPageGenerator = new CoverPageGenerator(EMPTY_PROJECT);
    assertThat(coverPageGenerator.getFilesToUpload().entryPoint())
        .isEqualTo(Paths.get("index.html"));
  }

  @Test
  void testGetFilesToUpload_containsEntryFile() {
    String content = getContentOfGeneratedFile(EMPTY_PROJECT, "index.html");
    assertThat(content)
        .contains(
            "<span class=\"project_invocation\">./gradlew :a:task1 :a:task2 -P key=value</span>");
  }

  @Test
  void testCoverPage_showsFailedTask() {
    String content = getCoverPage(EMPTY_PROJECT.toBuilder().addTask(EMPTY_TASK_FAILURE).build());
    assertThat(content).contains("task-failure");
    assertThat(content).contains("<p>FAILURE</p>");
    assertThat(content).doesNotContain("<p>SUCCESS</p>");
    assertThat(content).doesNotContain("<p>UP_TO_DATE</p>");
  }

  @Test
  void testCoverPage_showsSuccessfulTask() {
    String content = getCoverPage(EMPTY_PROJECT.toBuilder().addTask(EMPTY_TASK_SUCCESS).build());
    assertThat(content).contains("task-success");
    assertThat(content).doesNotContain("<p>FAILURE</p>");
    assertThat(content).contains("<p>SUCCESS</p>");
    assertThat(content).doesNotContain("<p>UP_TO_DATE</p>");
  }

  @Test
  void testCoverPage_showsUpToDateTask() {
    String content = getCoverPage(EMPTY_PROJECT.toBuilder().addTask(EMPTY_TASK_UP_TO_DATE).build());
    assertThat(content).contains("task-up-to-date");
    assertThat(content).doesNotContain("<p>FAILURE</p>");
    assertThat(content).doesNotContain("<p>SUCCESS</p>");
    assertThat(content).contains("<p>UP_TO_DATE</p>");
  }

  @Test
  void testCoverPage_failedAreFirst() {
    String content =
        getCoverPage(
            EMPTY_PROJECT.toBuilder()
                .addTask(EMPTY_TASK_UP_TO_DATE)
                .addTask(EMPTY_TASK_FAILURE)
                .addTask(EMPTY_TASK_SUCCESS)
                .build());
    assertThat(content).contains("<p>FAILURE</p>");
    assertThat(content).contains("<p>SUCCESS</p>");
    assertThat(content).contains("<p>UP_TO_DATE</p>");
    assertThat(content).containsMatch("(?s)<p>FAILURE</p>.*<p>SUCCESS</p>");
    assertThat(content).containsMatch("(?s)<p>FAILURE</p>.*<p>UP_TO_DATE</p>");
    assertThat(content).doesNotContainMatch("(?s)<p>SUCCESS</p>.*<p>FAILURE</p>");
    assertThat(content).doesNotContainMatch("(?s)<p>UP_TO_DATE</p>.*<p>FAILURE</p>");
  }

  @Test
  void testCoverPage_failingTask_statusIsFailure() {
    String content =
        getCoverPage(
            EMPTY_PROJECT.toBuilder()
                .addTask(EMPTY_TASK_UP_TO_DATE)
                .addTask(EMPTY_TASK_FAILURE)
                .addTask(EMPTY_TASK_SUCCESS)
                .build());
    assertThat(content).contains("<title>Failed: task-failure</title>");
  }

  @Test
  void testCoverPage_noFailingTask_statusIsSuccess() {
    String content =
        getCoverPage(
            EMPTY_PROJECT.toBuilder()
                .addTask(EMPTY_TASK_UP_TO_DATE)
                .addTask(EMPTY_TASK_SUCCESS)
                .build());
    assertThat(content).contains("<title>Success!</title>");
  }

  @Test
  void testGetFilesToUpload_containsCssFile() {
    ImmutableMap<String, String> files = getGeneratedFiles(EMPTY_PROJECT);
    assertThat(files).containsKey(filenameJoiner.join("css", "style.css"));
    assertThat(files.get(filenameJoiner.join("css", "style.css"))).contains("body {");
    assertThat(files.get("index.html"))
        .contains("<link rel=\"stylesheet\" type=\"text/css\" href=\"css/style.css\">");
  }

  @Test
  void testCreateReportFiles_taskWithLog() {
    ImmutableMap<String, String> files =
        getGeneratedFiles(
            EMPTY_PROJECT.toBuilder()
                .addTask(
                    EMPTY_TASK_SUCCESS.toBuilder()
                        .setUniqueName("my:name")
                        .setLog(toByteArraySupplier("my log data"))
                        .build())
                .build());
    assertThat(files).containsEntry(filenameJoiner.join("logs", "my-name.log"), "my log data");
    assertThat(files.get("index.html")).contains("<a href=\"logs/my-name.log\">[log]</a>");
  }

  @Test
  void testCreateReportFiles_taskWithoutLog() {
    ImmutableMap<String, String> files =
        getGeneratedFiles(
            EMPTY_PROJECT.toBuilder()
                .addTask(EMPTY_TASK_SUCCESS.toBuilder().setUniqueName("my:name").build())
                .build());
    assertThat(files).doesNotContainKey("logs/my-name.log");
    assertThat(files.get("index.html")).contains("<span class=\"report_link_broken\">[log]</span>");
  }

  @Test
  void testCreateReportFiles_taskWithFilledReport() {
    ImmutableMap<String, String> files =
        getGeneratedFiles(
            EMPTY_PROJECT.toBuilder()
                .addTask(
                    EMPTY_TASK_SUCCESS.toBuilder()
                        .putReport(
                            "someReport",
                            FilesWithEntryPoint.create(
                                ImmutableMap.of(
                                    Paths.get("path", "report.txt"),
                                    toByteArraySupplier("report content")),
                                Paths.get("path", "report.txt")))
                        .build())
                .build());
    assertThat(files).containsEntry(filenameJoiner.join("path", "report.txt"), "report content");
    assertThat(files.get("index.html")).contains("<a href=\"path/report.txt\">[someReport]</a>");
  }

  @Test
  void testCreateReportFiles_taskWithEmptyReport() {
    ImmutableMap<String, String> files =
        getGeneratedFiles(
            EMPTY_PROJECT.toBuilder()
                .addTask(
                    EMPTY_TASK_SUCCESS.toBuilder()
                        .putReport(
                            "someReport",
                            FilesWithEntryPoint.create(
                                ImmutableMap.of(), Paths.get("path", "report.txt")))
                        .build())
                .build());
    assertThat(files).doesNotContainKey(filenameJoiner.join("path", "report.txt"));
    assertThat(files.get("index.html"))
        .contains("<span class=\"report_link_broken\">[someReport]</span>");
  }

  @Test
  void testCreateReportFiles_taskWithLogAndMultipleReports() {
    ImmutableMap<String, String> files =
        getGeneratedFiles(
            EMPTY_PROJECT.toBuilder()
                .addTask(
                    EMPTY_TASK_SUCCESS.toBuilder()
                        .setUniqueName("my:name")
                        .setLog(toByteArraySupplier("log data"))
                        .putReport(
                            "filledReport",
                            FilesWithEntryPoint.create(
                                ImmutableMap.of(
                                    Paths.get("path-filled", "report.txt"),
                                    toByteArraySupplier("report content"),
                                    Paths.get("path-filled", "other", "file.txt"),
                                    toByteArraySupplier("some other content")),
                                Paths.get("path-filled", "report.txt")))
                        .putReport(
                            "emptyReport",
                            FilesWithEntryPoint.create(
                                ImmutableMap.of(), Paths.get("path-empty", "report.txt")))
                        .build())
                .build());
    assertThat(files)
        .containsEntry(filenameJoiner.join("path-filled", "report.txt"), "report content");
    assertThat(files)
        .containsEntry(
            filenameJoiner.join("path-filled", "other", "file.txt"), "some other content");
    assertThat(files).containsEntry(filenameJoiner.join("logs", "my-name.log"), "log data");
    assertThat(files.get("index.html"))
        .contains("<a href=\"path-filled/report.txt\">[filledReport]</a>");
    assertThat(files.get("index.html")).contains("<a href=\"logs/my-name.log\">[log]</a>");
    assertThat(files.get("index.html"))
        .contains("<span class=\"report_link_broken\">[emptyReport]</span>");
  }
}
