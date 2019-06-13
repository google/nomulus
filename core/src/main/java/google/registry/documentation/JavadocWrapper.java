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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import com.sun.javadoc.RootDoc;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javadoc.JavadocTool;
import com.sun.tools.javadoc.Messager;
import com.sun.tools.javadoc.ModifierFilter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.tools.StandardLocation;

/**
 * Wrapper class to simplify calls to the javadoc system and hide internal details.  An instance
 * represents a set of parameters for calling out to javadoc; these parameters can be set via
 * the appropriate methods, and determine what files and packages javadoc will process.  The
 * actual running of javadoc occurs when calling getRootDoc() to retrieve a javadoc RootDoc.
 */
public final class JavadocWrapper {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Shows any member visible at at least the default (package) level. */
  private static final long VISIBILITY_MASK =
      Modifier.PUBLIC | Modifier.PROTECTED | ModifierFilter.PACKAGE;

  /** Root directory for source files. If null, will use the current directory. */
  private static final String SOURCE_PATH = getProjectRoot().resolve("core/src/main/java")
      .toString();
  /** Specific source files to generate documentation for. */
  private static final ImmutableSet<String> SOURCE_FILE_NAMES = ImmutableSet.of();

  /** Specific packages to generate documentation for. */
  private static final ImmutableSet<String> SOURCE_PACKAGE_NAMES =
      ImmutableSet.of(FlowDocumentation.FLOW_PACKAGE_NAME);

  /** Whether or not the Javadoc tool should eschew excessive log output. */
  private static final boolean QUIET = true;

  /**
   * Obtains a Javadoc {@link RootDoc} object containing raw Javadoc documentation.
   * Wraps a call to the static method createRootDoc() and passes in instance-specific settings.
   */
  public static RootDoc getRootDoc() throws IOException {
    logger.atInfo().log("Starting Javadoc tool");
    File sourceFilePath = new File(SOURCE_PATH);
    logger.atInfo().log("Using source directory: %s", sourceFilePath.getAbsolutePath());
    try {
      return createRootDoc(
          SOURCE_PATH,
          SOURCE_PACKAGE_NAMES,
          SOURCE_FILE_NAMES,
          VISIBILITY_MASK,
          QUIET);
    } finally {
      logger.atInfo().log("Javadoc tool finished");
    }
  }

  /**
   * Obtains a Javadoc root document object for the specified source path and package/Java names.
   * If the source path is null, then the working directory is assumed as the source path.
   *
   * <p>If a list of package names is provided, then Javadoc will run on these packages and all
   * their subpackages, based out of the specified source path.
   *
   * <p>If a list of file names is provided, then Javadoc will also run on these Java source files.
   * The specified source path is not considered in this case.
   *
   * @see <a href="http://relation.to/12969.lace">Testing Java doclets</a>
   * @see <a href="http://www.docjar.com/docs/api/com/sun/tools/javadoc/JavadocTool.html">JavadocTool</a>
   */
  private static RootDoc createRootDoc(
      @Nullable String sourcePath,
      Collection<String> packageNames,
      Collection<String> fileNames,
      long visibilityMask,
      boolean quiet) throws IOException {
    // Create a context to hold settings for Javadoc.
    Context context = new Context();

    // Redirect Javadoc stdout/stderr to null writers, since otherwise the Java compiler
    // issues lots of errors for classes that are imported and visible to blaze but not
    // visible locally to the compiler.
    // TODO(b/19124943): Find a way to ignore those errors so we can show real ones?
    Messager.preRegister(
        context,
        JavadocWrapper.class.getName(),
        new PrintWriter(CharStreams.nullWriter()),     // For errors.
        new PrintWriter(CharStreams.nullWriter()),     // For warnings.
        new PrintWriter(CharStreams.nullWriter()));    // For notices.

    // Set source path option for Javadoc.
    try (JavacFileManager fileManager = new JavacFileManager(context, true, UTF_8)) {
      List<File> sourcePathFiles = new ArrayList<>();
      if (sourcePath != null) {
        for (String sourcePathEntry : Splitter.on(':').split(sourcePath)) {
          sourcePathFiles.add(new File(sourcePathEntry));
        }
      }
      fileManager.setLocation(StandardLocation.SOURCE_PATH, sourcePathFiles);

      // Create an instance of Javadoc.
      JavadocTool javadocTool = JavadocTool.make0(context);

      // Convert the package and file lists to a format Javadoc can understand.
      ListBuffer<String> subPackages = new ListBuffer<>();
      subPackages.addAll(packageNames);
      ListBuffer<String> javaNames = new ListBuffer<>();
      javaNames.addAll(fileNames);

      // Invoke Javadoc and ask it for a RootDoc containing the specified packages.
      return javadocTool.getRootDocImpl(
          Locale.US.toString(),                    // Javadoc comment locale
          UTF_8.name(),                            // Source character encoding
          new ModifierFilter(visibilityMask),      // Element visibility filter
          javaNames.toList(),                      // Included Java file names
          com.sun.tools.javac.util.List.nil(),     // Doclet options
          com.sun.tools.javac.util.List.nil(),     // Source files
          false,                                   // Don't use BreakIterator
          subPackages.toList(),                    // Included sub-package names
          com.sun.tools.javac.util.List.nil(),     // Excluded package names
          false,                                   // Read source files, not classes
          false,                                   // Don't run legacy doclet
          quiet);                                  // If asked, run Javadoc quietly
    }
  }

  private JavadocWrapper() {}
}
