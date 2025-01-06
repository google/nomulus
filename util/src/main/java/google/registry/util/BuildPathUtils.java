// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import java.nio.file.Path;
import java.nio.file.Paths;

/** Utilities methods related to build path. */
public final class BuildPathUtils {
  // When we run the build from gradlew's directory, the current working directory would be
  // ${projectRoot}/gradle/${subproject}. So, the project root is the grand parent of it.
  private static final Path PROJECT_ROOT =
      Paths.get(System.getProperty("test.projectRoot", "../")).normalize();
  private static final Path RESOURCES_DIR =
      Paths.get(System.getProperty("test.resourcesDir", "build/resources/main")).normalize();

  /** Returns the {@link Path} to the project root directory. */
  public static Path getProjectRoot() {
    return PROJECT_ROOT;
  }

  /** Returns the {@link Path} to the resources directory. */
  public static Path getResourcesDir() {
    return RESOURCES_DIR;
  }

  private BuildPathUtils() {}
}
