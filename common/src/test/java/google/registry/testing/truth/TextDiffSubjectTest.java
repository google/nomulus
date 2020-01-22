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

import static com.google.common.io.Resources.getResource;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.truth.TextDiffSubject.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import google.registry.testing.truth.TextDiffSubject.DiffFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TextDiffSubject}. */
@RunWith(JUnit4.class)
public class TextDiffSubjectTest {

  private static final String RESOURCE_FOLDER = "google/registry/testing/truth/";
  // Resources for input data.
  private static final String ACTUAL_RESOURCE = RESOURCE_FOLDER + "text-diff-actual.txt";
  private static final String EXPECTED_RESOURCE = RESOURCE_FOLDER + "text-diff-expected.txt";

  // Resources for expected diff texts.
  private static final String UNIFIED_DIFF_RESOURCE = RESOURCE_FOLDER + "text-unified-diff.txt";
  private static final String SIDE_BY_SIDE_DIFF_RESOURCE =
      RESOURCE_FOLDER + "text-sidebyside-diff.txt";

  @Test
  public void unifiedDiff_equal() throws IOException {
    assertThat(getResource(ACTUAL_RESOURCE))
        .withDiffFormat(DiffFormat.UNIFIED_DIFF)
        .hasSameContentAs(getResource(ACTUAL_RESOURCE));
  }

  @Test
  public void sideBySideDiff_equal() throws IOException {
    assertThat(getResource(ACTUAL_RESOURCE))
        .withDiffFormat(DiffFormat.SIDE_BY_SIDE_MARKDOWN)
        .hasSameContentAs(getResource(ACTUAL_RESOURCE));
  }

  @Test
  public void unifedDiff_notEqual() throws IOException {
    assertThrows(
        AssertionError.class,
        () ->
            assertThat(getResource(ACTUAL_RESOURCE))
                .withDiffFormat(DiffFormat.UNIFIED_DIFF)
                .hasSameContentAs(getResource(EXPECTED_RESOURCE)));
  }

  @Test
  public void sideBySideDiff_notEqual() throws IOException {
    assertThrows(
        AssertionError.class,
        () ->
            assertThat(getResource(ACTUAL_RESOURCE))
                .withDiffFormat(DiffFormat.SIDE_BY_SIDE_MARKDOWN)
                .hasSameContentAs(getResource(EXPECTED_RESOURCE)));
  }

  @Test
  public void displayed_unifiedDiff_noDiff() throws IOException {
    ImmutableList<String> actual = readAllLinesFromResource(ACTUAL_RESOURCE);
    assertThat(TextDiffSubject.generateUnifiedDiff(actual, actual)).isEqualTo("");
  }

  @Test
  public void displayed_unifiedDiff_hasDiff() throws IOException {
    ImmutableList<String> actual = readAllLinesFromResource(ACTUAL_RESOURCE);
    ImmutableList<String> expected = readAllLinesFromResource(EXPECTED_RESOURCE);
    String diff = Joiner.on('\n').join(readAllLinesFromResource(UNIFIED_DIFF_RESOURCE));
    assertThat(TextDiffSubject.generateUnifiedDiff(expected, actual)).isEqualTo(diff);
  }

  @Test
  public void displayed_sideBySideDiff_hasDiff() throws IOException {
    ImmutableList<String> actual = readAllLinesFromResource(ACTUAL_RESOURCE);
    ImmutableList<String> expected = readAllLinesFromResource(EXPECTED_RESOURCE);
    String diff = Joiner.on('\n').join(readAllLinesFromResource(SIDE_BY_SIDE_DIFF_RESOURCE));
    assertThat(TextDiffSubject.generateSideBySideDiff(expected, actual)).isEqualTo(diff);
  }

  private static ImmutableList<String> readAllLinesFromResource(String resourceName)
      throws IOException {
    return ImmutableList.copyOf(
        Resources.readLines(getResource(resourceName), StandardCharsets.UTF_8));
  }
}
