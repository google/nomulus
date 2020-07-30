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

package google.registry.documentation;

import static google.registry.util.BuildPathUtils.getProjectRoot;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.File;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import javax.tools.StandardLocation;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.internal.tool.AccessKind;
import jdk.javadoc.internal.tool.JavadocTool;
import jdk.javadoc.internal.tool.Messager;
import jdk.javadoc.internal.tool.ToolOption;

/**
 * Wrapper class to simplify calls to the javadoc system and hide internal details. An instance
 * represents a set of parameters for calling out to javadoc; these parameters can be set via the
 * appropriate methods, and determine what files and packages javadoc will process. The actual
 * running of javadoc occurs when calling getRootDoc() to retrieve a javadoc RootDoc.
 */
public final class JavadocWrapper {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Shows any member visible at at least the default (package) level. */
  private static final AccessKind ACCESS_KIND = AccessKind.PACKAGE;

  /** Root directory for source files. */
  public static final String SOURCE_PATH =
      getProjectRoot().resolve("core/src/main/java").toString();

  /** Specific packages to generate documentation for. */
  private static final ImmutableList<String> SOURCE_PACKAGE_NAMES =
      ImmutableList.of(FlowDocumentation.FLOW_PACKAGE_NAME);

  /**
   * Obtains a Javadoc {@link DocletEnvironment} object containing raw Javadoc documentation. Wraps
   * a call to the static method {@link #createDocletEnv} and passes in instance-specific settings.
   */
  public static DocletEnvironment getDocletEnv() throws Exception {
    logger.atInfo().log("Starting Javadoc tool");
    File sourceFilePath = new File(SOURCE_PATH);
    logger.atInfo().log("Using source directory: %s", sourceFilePath.getAbsolutePath());
    try {
      return createDocletEnv(SOURCE_PATH, SOURCE_PACKAGE_NAMES);
    } finally {
      logger.atInfo().log("Javadoc tool finished");
    }
  }

  /**
   * Obtains a Javadoc {@link DocletEnvironment} object for the specified source path and
   * package/Java names. If the source path is null, then the working directory is assumed as the
   * source path.
   *
   * <p>If a list of package names is provided, then Javadoc will run on these packages and all
   * their subpackages, based out of the specified source path.
   *
   * <p>If a list of file names is provided, then Javadoc will also run on these Java source files.
   * The specified source path is not considered in this case.
   *
   * @param sourcePath the directory where to look for packages.
   * @param packageNames name of the package to run javadoc on, including subpackages.
   * @see <a
   *     href="https://docs.oracle.com/javase/9/docs/api/jdk/javadoc/doclet/package-summary.html">
   *     Package jdk.javadoc.doclet</a>
   */
  private static DocletEnvironment createDocletEnv(
      String sourcePath, Collection<String> packageNames) throws Exception {

    // Create a context to hold settings for Javadoc.
    Context context = new Context();

    // Pre-register a messager for the context.
    Messager.preRegister(context, JavadocWrapper.class.getName());

    // Set source path option for Javadoc.
    try (JavacFileManager fileManager = new JavacFileManager(context, true, UTF_8)) {

      fileManager.setLocation(StandardLocation.SOURCE_PATH, ImmutableList.of(new File(sourcePath)));

      // Create an instance of Javadoc.
      JavadocTool javadocTool = JavadocTool.make0(context);

      // Set up javadoc tool options.
      Map<ToolOption, Object> options = new EnumMap<>(ToolOption.class);
      options.put(ToolOption.SHOW_PACKAGES, ACCESS_KIND);
      options.put(ToolOption.SHOW_TYPES, ACCESS_KIND);
      options.put(ToolOption.SHOW_MEMBERS, ACCESS_KIND);
      options.put(ToolOption.SHOW_MODULE_CONTENTS, ACCESS_KIND);
      options.put(ToolOption.SUBPACKAGES, packageNames);

      // Invoke Javadoc and ask it for a DocletEnvironment containing the specified packages.
      return javadocTool.getEnvironment(
          options, // options
          ImmutableList.of(), // java names
          ImmutableList.of()); // java files
    }
  }

  private JavadocWrapper() {}
}
