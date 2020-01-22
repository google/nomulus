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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test conversion of javadocs to markdown. */
@RunWith(JUnit4.class)
public class MarkdownDocumentationFormatterTest {
  @Test
  public void testHtmlSanitization() {
    assertThat(
            MarkdownDocumentationFormatter.fixHtml(
                "First. <p>Second. &lt; &gt; &amp; &squot; &quot;"))
        .isEqualTo("First. Second. < > & ' \"");
    assertThat(MarkdownDocumentationFormatter.fixHtml("<p>Leading substitution."))
        .isEqualTo("Leading substitution.");
    assertThat(MarkdownDocumentationFormatter.fixHtml("No substitution."))
        .isEqualTo("No substitution.");
  }

  @Test
  public void testDedents() {
    assertThat(MarkdownDocumentationFormatter.fixHtml(
        "First line\n\n <p>Second line.\n Third line."))
        .isEqualTo("First line\n\nSecond line.\nThird line.");
  }

  @Test
  public void testUnknownSequences() {
    assertThrows(
        IllegalArgumentException.class, () -> MarkdownDocumentationFormatter.fixHtml("&blech;"));
  }

  @Test
  public void testParagraphFormatting() {
    String[] words = {"first", "second", "third", "really-really-long-word", "more", "stuff"};
    String formatted = MarkdownDocumentationFormatter.formatParagraph(Arrays.asList(words), 16);
    assertThat(formatted).isEqualTo("first second\nthird\nreally-really-long-word\nmore stuff\n");
  }

  @Test
  public void testReflow() {
    String input =
        "This is the very first line.\n"
        + "  \n"  // add a little blank space to this line just to make things interesting.
        + "This is the second paragraph.  Aint\n"
        + "it sweet?\n"
        + "\n"
        + "This is our third and final paragraph.\n"
        + "It is multi-line and ends with no blank\n"
        + "line.";

    String expected =
        "This is the very\n"
        + "first line.\n"
        + "\n"
        + "This is the\n"
        + "second\n"
        + "paragraph. Aint\n"
        + "it sweet?\n"
        + "\n"
        + "This is our\n"
        + "third and final\n"
        + "paragraph. It is\n"
        + "multi-line and\n"
        + "ends with no\n"
        + "blank line.\n";

      assertThat(MarkdownDocumentationFormatter.reflow(input, 16)).isEqualTo(expected);
    }

  public MarkdownDocumentationFormatterTest() {}
}
