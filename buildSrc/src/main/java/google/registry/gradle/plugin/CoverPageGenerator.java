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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.io.Resources.getResource;
import static google.registry.gradle.plugin.GcsPluginUtils.toByteArraySupplier;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.gradle.plugin.ProjectData.TaskData;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Creates the files for a web-page summary of a given {@Link ProjectData}.
 *
 * <p>The main job of this class is rendering a tailored cover page that includes information about
 * the project and any task that ran.
 *
 * <p>It returns all the files that need uploading for the cover page to work. This includes any
 * report and log files linked to in the ProjectData, as well as a cover page (and associated
 * resources such as CSS files).
 */
final class CoverPageGenerator {

  /** List of all resource files that will be uploaded as-is. */
  private static final ImmutableSet<Path> STATIC_RESOURCE_FILES =
      ImmutableSet.of(Paths.get("css", "style.css"));
  /** Name of the entry-point file that will be created. */
  private static final Path ENTRY_POINT = Paths.get("index.html");

  private final ProjectData projectData;
  private final ImmutableSetMultimap<TaskData.State, TaskData> tasksByState;
  /**
   * The compiled SOY files.
   *
   * <p>Will be generated only when actually needed, because it takes a while to compile and we
   * don't want that to happen unless we actually use it.
   */
  private SoyTofu tofu = null;

  CoverPageGenerator(ProjectData projectData) {
    this.projectData = projectData;
    this.tasksByState =
        projectData.tasks().stream().collect(toImmutableSetMultimap(TaskData::state, task -> task));
  }

  /**
   * Returns all the files that need uploading for the cover page to work.
   *
   * <p>This includes all the report files as well, to make sure that the link works.
   */
  FilesWithEntryPoint getFilesToUpload() {
    ImmutableMap.Builder<Path, Supplier<byte[]>> builder = new ImmutableMap.Builder<>();
    // Add all the static resource pages
    STATIC_RESOURCE_FILES.stream().forEach(file -> builder.put(file, resourceLoader(file)));
    // Create the cover page
    // Note that the ByteArraySupplier here is lazy - the createCoverPage function is only called
    // when the resulting Supplier's get function is called.
    builder.put(ENTRY_POINT, toByteArraySupplier(this::createCoverPage));
    // Add all the files from the tasks
    tasksByState.values().stream()
        .flatMap(task -> task.reports().values().stream())
        .forEach(reportFiles -> builder.putAll(reportFiles.files()));
    // Add the logs of every test
    tasksByState.values().stream()
        .filter(task -> task.log().isPresent())
        .forEach(task -> builder.put(getLogPath(task), task.log().get()));

    return FilesWithEntryPoint.create(builder.build(), ENTRY_POINT);
  }

  /** Renders the cover page. */
  private String createCoverPage() {
    return getTofu()
        .newRenderer("google.registry.gradle.plugin.coverPage")
        .setData(getSoyData())
        .render();
  }

  /** Converts the projectData and all taskData into all the data the soy template needs. */
  private ImmutableMap<String, Object> getSoyData() {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();

    TaskData.State state =
        tasksByState.containsKey(TaskData.State.FAILURE)
            ? TaskData.State.FAILURE
            : TaskData.State.SUCCESS;
    String title =
        state != TaskData.State.FAILURE
            ? "Success!"
            : "Failed: "
                + tasksByState.get(state).stream()
                    .map(TaskData::uniqueName)
                    .collect(Collectors.joining(", "));

    builder.put("projectState", state.toString());
    builder.put("title", title);
    builder.put("cssFiles", ImmutableSet.of("css/style.css"));
    builder.put("invocation", getInvocation());
    builder.put("tasksByState", getTasksByStateSoyData());
    return builder.build();
  }

  /**
   * Returns a soy-friendly map from the TaskData.State to the task itslef.
   *
   * <p>The key order in the resulting map is always the same (the order from the enum definition)
   * no matter the key order in the original tasksByState map.
   */
  private ImmutableMap<String, Object> getTasksByStateSoyData() {
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();

    // We go over the States in the order they are defined rather than the order in which they
    // happen to be in the tasksByState Map.
    //
    // That way we guarantee a consistent order.
    for (TaskData.State state : TaskData.State.values()) {
      builder.put(
          state.toString(),
          tasksByState.get(state).stream()
              .map(task -> taskDataToSoy(task))
              .collect(toImmutableList()));
    }

    return builder.build();
  }

  /** returns a soy-friendly version of the given task data. */
  static ImmutableMap<String, Object> taskDataToSoy(TaskData task) {
    // Note that all instances of File.separator are replaced with forward slashes so that we can
    // generate a valid href on Windows.
    return new ImmutableMap.Builder<String, Object>()
        .put("uniqueName", task.uniqueName())
        .put("description", task.description())
        .put(
            "log",
            task.log().isPresent() ? getLogPath(task).toString().replace(File.separator, "/") : "")
        .put(
            "reports",
            task.reports().entrySet().stream()
                .collect(
                    toImmutableMap(
                        Map.Entry::getKey,
                        entry ->
                            entry.getValue().files().isEmpty()
                                ? ""
                                : entry
                                    .getValue()
                                    .entryPoint()
                                    .toString()
                                    .replace(File.separator, "/"))))
        .build();
  }

  private String getInvocation() {
    StringBuilder builder = new StringBuilder();
    builder.append("./gradlew");
    projectData.tasksRequested().forEach(task -> builder.append(" ").append(task));
    projectData
        .projectProperties()
        .forEach((key, value) -> builder.append(String.format(" -P %s=%s", key, value)));
    return builder.toString();
  }

  /** Returns a lazily created soy renderer */
  private SoyTofu getTofu() {
    if (tofu == null) {
      tofu =
          SoyFileSet.builder()
              .add(getResource(CoverPageGenerator.class, "soy/coverpage.soy"))
              .build()
              .compileToTofu();
    }
    return tofu;
  }

  private static Path getLogPath(TaskData task) {
    // We replace colons with dashes so that the resulting filename is always valid, even in
    // Windows. As a dash is not a valid character in Java identifies, a task name cannot include
    // it, so the uniqueness of the name is perserved.
    return Paths.get("logs", task.uniqueName().replace(":", "-") + ".log");
  }

  private static Supplier<byte[]> resourceLoader(Path path) {
    return toByteArraySupplier(getResource(CoverPageGenerator.class, path.toString()));
  }
}
