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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static google.registry.util.BuildPathUtils.getProjectRoot;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.thoughtworks.qdox.JavaDocBuilder;
import com.thoughtworks.qdox.model.JavaSource;
import google.registry.documentation.FlowDocumentation.ErrorCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Stores the context for a flow and computes exception mismatches between javadoc and tests.
 *
 * <p>This class uses the flow_docs library built for the documentation generator tool to pull out
 * the set of flow exceptions documented by custom javadoc tags on the specified flow.  It then
 * derives the corresponding test files for that flow and pulls out the imported names from those
 * files, checking against a set of all possible flow exceptions to determine those used by this
 * particular test.  The set of javadoc-based exceptions and the set of imported exceptions should
 * be identical, ensuring a correspondence between error cases listed in the documentation and
 * those tested in the flow unit tests.
 *
 * <p>If the two sets are not identical, the getMismatchedExceptions() method on this class will
 * return a non-empty string containing messages about what the mismatches were and which lines
 * need to be added or removed in which files in order to satisfy the correspondence condition.
 */
public class FlowContext {
  /** Represents one of the two possible places an exception may be referenced from. */
  // TODO(b/19124943): This enum is only used in ErrorCaseMismatch and ideally belongs there, but
  // can't go in the inner class because it's not a static inner class.  At some point it might
  // be worth refactoring so that this enum can be properly scoped.
  private enum SourceType { JAVADOC, IMPORT }

  /** The package in which this flow resides. */
  final String packageName;

  /** The source file for this flow, used for help messages. */
  final String sourceFilename;

  /** The test files for this flow, used for help messages and extracting imported exceptions. */
  final Set<String> testFilenames;

  /** The set of all possible exceptions that could be error cases for a flow. */
  final Set<ErrorCase> possibleExceptions;

  /** The set of exceptions referenced from the javadoc on this flow. */
  final Set<ErrorCase> javadocExceptions;

  /** Maps exceptions imported by the test files for this flow to the files in which they occur. */
  final SetMultimap<ErrorCase, String> importExceptionsToFilenames;

  /**
   * Creates a FlowContext from a FlowDocumentation object and a set of all possible exceptions.
   * The latter parameter is needed in order to filter imported names in the flow test file.
   */
  public FlowContext(FlowDocumentation flowDoc, Set<ErrorCase> possibleExceptions)
      throws IOException {
    packageName = flowDoc.getPackageName();
    // Assume the standard filename conventions for locating the flow class's source file.
    sourceFilename = "java/" + flowDoc.getQualifiedName().replace('.', '/') + ".java";
    testFilenames = getTestFilenames(flowDoc.getQualifiedName());
    checkState(testFilenames.size() >= 1, "No test files found for %s.", flowDoc.getName());
    this.possibleExceptions = possibleExceptions;
    javadocExceptions = Sets.newHashSet(flowDoc.getErrors());
    importExceptionsToFilenames = getImportExceptions();
  }

  /**
   * Helper to locate test files for this flow by looking in src/test/java/ for all files with the
   * exact same relative filename as the flow file, but with a "*Test{,Case}.java" suffix.
   */
  private static Set<String> getTestFilenames(String flowName) throws IOException {
    String commonPrefix =
        getProjectRoot().resolve("core/src/test/java").resolve(flowName.replace('.', '/'))
            .toString();
    return Sets.union(
        getFilenamesMatchingGlob(commonPrefix + "*Test.java"),
        getFilenamesMatchingGlob(commonPrefix + "*TestCase.java"));
  }

  /**
   * Helper to return the set of filenames matching the given glob.  The glob should only have
   * asterisks in the portion following the last slash (if there is one).
   */
  private static Set<String> getFilenamesMatchingGlob(String fullGlob) throws IOException {
    Path globPath = FileSystems.getDefault().getPath(fullGlob);
    Path dirPath = globPath.getParent();
    String glob = globPath.getFileName().toString();
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath, glob)) {
      return Streams.stream(dirStream).map(Object::toString).collect(toImmutableSet());
    }
  }

  /**
   * Returns a multimap mapping each exception imported in test files for this flow to the set of
   * filenames for files that import that exception.
   */
  private SetMultimap<ErrorCase, String> getImportExceptions() throws IOException {
    ImmutableMultimap.Builder<String, ErrorCase> builder = new ImmutableMultimap.Builder<>();
    for (String testFileName : testFilenames) {
      builder.putAll(testFileName, getImportExceptionsFromFile(testFileName));
    }
    // Invert the mapping so that later we can easily map exceptions to where they were imported.
    return MultimapBuilder.hashKeys().hashSetValues().build(builder.build().inverse());
  }

  /**
   * Returns the set of exceptions imported in this test file.  First extracts the set of
   * all names imported by the test file, and then uses these to filter a global list of possible
   * exceptions, so that the additional exception information available via the global list objects
   * (which are ErrorCases wrapping exception names) can be preserved.
   */
  private Set<ErrorCase> getImportExceptionsFromFile(String filename) throws IOException {
    JavaDocBuilder builder = new JavaDocBuilder();
    JavaSource src = builder.addSource(new File(filename));
    final Set<String> importedNames = Sets.newHashSet(src.getImports());
    return possibleExceptions
        .stream()
        .filter(errorCase -> importedNames.contains(errorCase.getClassName()))
        .collect(toImmutableSet());
  }


  /**
   * Represents a mismatch in this flow for a specific error case and documents how to fix it.
   * A mismatch occurs when the exception for this error case appears in either the source file
   * javadoc or at least one matching test file, but not in both.
   */
  private class ErrorCaseMismatch {

    /** The format for an import statement for a given exception name. */
    static final String IMPORT_FORMAT = "import %s;";

    /** The format for a javadoc tag referencing a given exception name. */
    static final String JAVADOC_FORMAT = "@error {@link %s}";

    // Template strings for printing output.
    static final String TEMPLATE_HEADER = "Extra %s for %s:\n";
    static final String TEMPLATE_ADD = "  Add %s to %s:\n  + %s\n";
    static final String TEMPLATE_ADD_MULTIPLE = "  Add %s to one or more of:\n%s  + %s\n";
    static final String TEMPLATE_REMOVE = "  Or remove %s in %s:\n  - %s\n";
    static final String TEMPLATE_REMOVE_MULTIPLE = "  Or remove %ss in:\n%s  - %s\n";
    static final String TEMPLATE_MULTIPLE_FILES = "    * %s\n";

    /** The error case for which the mismatch was detected. */
    final ErrorCase errorCase;

    /** The source type where references could be added to fix the mismatch. */
    final SourceType addType;

    /** The source type where references could be removed to fix the mismatch. */
    final SourceType removeType;

    /**
     * Constructs an ErrorCaseMismatch for the given ErrorCase and SourceType.  The latter parameter
     * indicates the source type this exception was referenced from.
     */
    public ErrorCaseMismatch(ErrorCase errorCase, SourceType foundType) {
      this.errorCase = errorCase;
      // Effectively addType = !foundType.
      addType = (foundType == SourceType.IMPORT ? SourceType.JAVADOC : SourceType.IMPORT);
      removeType = foundType;
    }

    /** Returns the line of code needed to refer to this exception from the given source type. */
    public String getCodeLineAs(SourceType sourceType) {
      return sourceType == SourceType.JAVADOC
          // Strip the flow package prefix from the exception class name if possible, for brevity.
          ? String.format(JAVADOC_FORMAT, errorCase.getClassName().replace(packageName + ".", ""))
          : String.format(IMPORT_FORMAT, errorCase.getClassName());
    }

    /** Helper to format a set of filenames for printing in a mismatch message. */
    private String formatMultipleFiles(Set<String> filenames) {
      checkArgument(filenames.size() >= 1, "Cannot format empty list of files.");
      if (filenames.size() == 1) {
        return filenames.stream().collect(onlyElement());
      }
      return filenames
          .stream()
          .map(filename -> String.format(TEMPLATE_MULTIPLE_FILES, filename))
          .collect(joining(""));
    }

    /** Helper to format the section describing how to add references to fix the mismatch. */
    private String makeAddSection() {
      String addTypeString = Ascii.toLowerCase(addType.toString());
      String codeLine = getCodeLineAs(addType);
      Set<String> files = (addType == SourceType.JAVADOC
          ? ImmutableSet.of(sourceFilename)
          : testFilenames);
      return (files.size() == 1
          ? String.format(
              TEMPLATE_ADD, addTypeString, formatMultipleFiles(files), codeLine)
          : String.format(
              TEMPLATE_ADD_MULTIPLE, addTypeString, formatMultipleFiles(files), codeLine));
    }

    /** Helper to format the section describing how to remove references to fix the mismatch. */
    // TODO(b/19124943): Repeating structure from makeAddSection() - would be nice to clean up.
    private String makeRemoveSection() {
      String removeTypeString = Ascii.toLowerCase(removeType.toString());
      String codeLine = getCodeLineAs(removeType);
      Set<String> files = (removeType == SourceType.JAVADOC
          ? ImmutableSet.of(sourceFilename)
          : importExceptionsToFilenames.get(errorCase));
      return (files.size() == 1
          ? String.format(
              TEMPLATE_REMOVE, removeTypeString, formatMultipleFiles(files), codeLine)
          : String.format(
              TEMPLATE_REMOVE_MULTIPLE, removeTypeString, formatMultipleFiles(files), codeLine));
    }

    /** Returns a string describing the mismatch for this flow exception and how to fix it. */
    @Override
    public String toString() {
      String headerSection = String.format(
          TEMPLATE_HEADER, Ascii.toLowerCase(removeType.toString()), errorCase.getName());
      return headerSection + makeAddSection() + makeRemoveSection();
    }
  }

  /**
   * Returns a single string describing all mismatched exceptions for this flow.  An empty string
   * means no mismatched exceptions were found.
   */
  public String getMismatchedExceptions() {
    Set<ErrorCase> importExceptions = importExceptionsToFilenames.keySet();
    StringBuilder builder = new StringBuilder();
    for (ErrorCase errorCase : Sets.difference(javadocExceptions, importExceptions)) {
      builder.append(new ErrorCaseMismatch(errorCase, SourceType.JAVADOC)).append("\n");
    }
    for (ErrorCase errorCase : Sets.difference(importExceptions, javadocExceptions)) {
      builder.append(new ErrorCaseMismatch(errorCase, SourceType.IMPORT)).append("\n");
    }
    return builder.toString();
  }
}
