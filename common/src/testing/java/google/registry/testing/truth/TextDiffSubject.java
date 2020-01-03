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

package google.registry.testing.truth;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.Patch;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.common.truth.Fact;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compares two multi-line text blocks, and displays their diffs in readable formats.
 *
 * <p>User may choose one of the following diff formats:
 *
 * <ul>
 *   <li>{@link DiffFormat#UNIFIED_DIFF} displays the differences in the unified-diff format
 *   <li>{@link DiffFormat#SIDE_BY_SIDE_MARKDOWN} displays the two text blocks side by side, with
 *       markdown annotations to highlight the differences.
 * </ul>
 *
 * <p>Note that if one text block has one trailing newline at the end while another has none, this
 * difference will not be shown in the generated diffs. This is the case where two texts may be
 * reported as unequal but the diffs appear equal. Fixing this requires special treatment of the
 * last line of text. The fix would not be useful in our environment, where all important files are
 * covered by a style checker that ensures the presence of a trailing newline.
 */
// TODO(weiminyu): move this class and test to a standalone 'testing' project. Note that the util
// project is not good since it depends back to core.
public class TextDiffSubject extends Subject {

  private final ImmutableList<String> actual;
  private DiffFormat diffFormat = DiffFormat.SIDE_BY_SIDE_MARKDOWN;

  protected TextDiffSubject(FailureMetadata metadata, List<String> actual) {
    super(metadata, actual);
    this.actual = ImmutableList.copyOf(actual);
  }

  public TextDiffSubject withDiffFormat(DiffFormat format) {
    this.diffFormat = format;
    return this;
  }

  public void hasSameContentAs(List<String> expectedContent) {
    checkNotNull(expectedContent, "expectedContent");
    ImmutableList<String> expected = ImmutableList.copyOf(expectedContent);
    if (expected.equals(actual)) {
      return;
    }
    String diffString = diffFormat.generateDiff(expected, actual);
    failWithoutActual(
        Fact.simpleFact(
            Joiner.on('\n')
                .join(
                    "Files differ in content. Displaying " + Ascii.toLowerCase(diffFormat.name()),
                    diffString)));
  }

  public void hasSameContentAs(URL resourceUrl) throws IOException {
    hasSameContentAs(Resources.asCharSource(resourceUrl, UTF_8).readLines());
  }

  public static TextDiffSubject assertThat(List<String> actual) {
    return assertAbout(textFactory()).that(ImmutableList.copyOf(checkNotNull(actual, "actual")));
  }

  public static TextDiffSubject assertThat(URL resourceUrl) throws IOException {
    return assertThat(Resources.asCharSource(resourceUrl, UTF_8).readLines());
  }

  private static final Subject.Factory<TextDiffSubject, ImmutableList<String>>
      TEXT_DIFF_SUBJECT_TEXT_FACTORY = TextDiffSubject::new;

  public static Subject.Factory<TextDiffSubject, ImmutableList<String>> textFactory() {
    return TEXT_DIFF_SUBJECT_TEXT_FACTORY;
  }

  static String generateUnifiedDiff(
      ImmutableList<String> expectedContent, ImmutableList<String> actualContent) {
    Patch<String> diff;
    try {
      diff = DiffUtils.diff(expectedContent, actualContent);
    } catch (DiffException e) {
      throw new RuntimeException(e);
    }
    List<String> unifiedDiff =
        UnifiedDiffUtils.generateUnifiedDiff("expected", "actual", expectedContent, diff, 0);

    return Joiner.on('\n').join(unifiedDiff);
  }

  static String generateSideBySideDiff(
      ImmutableList<String> expectedContent, ImmutableList<String> actualContent) {
    DiffRowGenerator generator =
        DiffRowGenerator.create()
            .showInlineDiffs(true)
            .inlineDiffByWord(true)
            .oldTag(f -> "~")
            .newTag(f -> "**")
            .build();
    List<DiffRow> rows;
    try {
      rows = generator.generateDiffRows(expectedContent, actualContent);
    } catch (DiffException e) {
      throw new RuntimeException(e);
    }

    int maxExpectedLineLength =
        findMaxLineLength(rows.stream().map(DiffRow::getOldLine).collect(Collectors.toList()));
    int maxActualLineLength =
        findMaxLineLength(rows.stream().map(DiffRow::getNewLine).collect(Collectors.toList()));

    SideBySideRowFormatter sideBySideRowFormatter =
        new SideBySideRowFormatter(maxExpectedLineLength, maxActualLineLength);

    return Joiner.on('\n')
        .join(
            sideBySideRowFormatter.formatRow("Expected", "Actual", ' '),
            sideBySideRowFormatter.formatRow("", "", '-'),
            rows.stream()
                .map(
                    row ->
                        sideBySideRowFormatter.formatRow(row.getOldLine(), row.getNewLine(), ' '))
                .toArray());
  }

  private static int findMaxLineLength(Collection<String> lines) {
    return lines.stream()
        .max(Comparator.comparingInt(String::length))
        .map(String::length)
        .orElse(0);
  }

  private static class SideBySideRowFormatter {
    private final int maxExpectedLineLength;
    private final int maxActualLineLength;

    private SideBySideRowFormatter(int maxExpectedLineLength, int maxActualLineLength) {
      this.maxExpectedLineLength = maxExpectedLineLength;
      this.maxActualLineLength = maxActualLineLength;
    }

    public String formatRow(String expected, String actual, char padChar) {
      return String.format(
          "|%s|%s|",
          Strings.padEnd(expected, maxExpectedLineLength, padChar),
          Strings.padEnd(actual, maxActualLineLength, padChar));
    }
  }

  /** The format used to display diffs when two text blocks are different. */
  public enum DiffFormat {
    UNIFIED_DIFF {
      @Override
      String generateDiff(ImmutableList<String> expected, ImmutableList<String> actual) {
        return generateUnifiedDiff(expected, actual);
      }
    },
    SIDE_BY_SIDE_MARKDOWN {
      @Override
      String generateDiff(ImmutableList<String> expected, ImmutableList<String> actual) {
        return generateSideBySideDiff(expected, actual);
      }
    };

    abstract String generateDiff(ImmutableList<String> expected, ImmutableList<String> actual);
  }
}
