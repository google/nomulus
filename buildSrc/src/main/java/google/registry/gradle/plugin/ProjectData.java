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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.gradle.plugin.ProjectData.TaskData;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * All the data of a root Gradle project.
 *
 * <p>This is basically all the "relevant" data from a Gradle Project, arranged in an immutable and
 * more convenient way.
 */
@AutoValue
abstract class ProjectData {

  abstract String name();

  abstract String description();

  abstract String gradleVersion();

  abstract ImmutableMap<String, String> projectProperties();

  abstract ImmutableMap<String, String> systemProperties();

  abstract ImmutableSet<String> tasksRequested();

  abstract ImmutableSet<TaskData> tasks();

  abstract Builder toBuilder();

  static Builder builder() {
    return new AutoValue_ProjectData.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setName(String name);

    abstract Builder setDescription(String description);

    abstract Builder setGradleVersion(String gradleVersion);

    abstract Builder setProjectProperties(Map<String, String> projectProperties);

    abstract Builder setSystemProperties(Map<String, String> systemProperties);

    abstract Builder setTasksRequested(Iterable<String> tasksRequested);

    abstract ImmutableSet.Builder<TaskData> tasksBuilder();

    Builder addTask(TaskData task) {
      tasksBuilder().add(task);
      return this;
    }

    abstract ProjectData build();
  }

  /**
   * Relevant data to a single Task's.
   *
   * <p>Some Tasks are also "Reporting", meaning they create file outputs we want to upload in
   * various formats. The format that interests us the most is "html", as that's nicely browsable,
   * but they might also have other formats.
   */
  @AutoValue
  abstract static class TaskData {

    enum State {
      /** The task has failed for some reason. */
      FAILURE,
      /** The task was actually run and has finished successfully. */
      SUCCESS,
      /** The task was up-to-date and successful, and hence didn't need to run again. */
      UP_TO_DATE
    }

    abstract String uniqueName();

    abstract String description();

    abstract State state();

    abstract Optional<Supplier<byte[]>> log();

    /**
     * Returns the FilesWithEntryPoint for every report, keyed on the report type.
     *
     * <p>The "html" report type is the most interesting, but there are other report formats.
     */
    abstract ImmutableMap<String, FilesWithEntryPoint> reports();

    abstract Builder toBuilder();

    static Builder builder() {
      return new AutoValue_ProjectData_TaskData.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setUniqueName(String name);

      abstract Builder setDescription(String description);

      abstract Builder setState(State state);

      abstract Builder setLog(Supplier<byte[]> log);

      abstract ImmutableMap.Builder<String, FilesWithEntryPoint> reportsBuilder();

      Builder putReport(String type, FilesWithEntryPoint reportFiles) {
        reportsBuilder().put(type, reportFiles);
        return this;
      }

      abstract TaskData build();
    }
  }
}
