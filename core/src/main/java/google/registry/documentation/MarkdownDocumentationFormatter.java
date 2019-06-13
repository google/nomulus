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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.documentation.FlowDocumentation.ErrorCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Formatter that converts flow documentation into Markdown.
 */
public final class MarkdownDocumentationFormatter {

  /** Header for flow documentation HTML output. */
  private static final String MARKDOWN_HEADER = "# Nomulus EPP Command API Documentation\n\n";

  /** Pattern that naively matches HTML tags and entity references. */
  private static final Pattern HTML_TAG_AND_ENTITY_PATTERN = Pattern.compile("<[^>]*>|&[^;]*;");

  /** 8 character indentation. */
  private static final String INDENT8 = Strings.repeat(" ", 8);

  /** Max linewidth for our markdown docs. */
  private static final int LINE_WIDTH = 80;

  /**
   * Returns the string with all HTML tags stripped.  Also, removes a single space after any
   * newlines that have one (we get a single space indent for all lines but the first because of
   * the way that javadocs are written in comments).
   */
  @VisibleForTesting
  static String fixHtml(String value) {
    Matcher matcher = HTML_TAG_AND_ENTITY_PATTERN.matcher(value);
    int pos = 0;
    StringBuilder result = new StringBuilder();
    while (matcher.find(pos)) {
      result.append(value, pos, matcher.start());
      switch (matcher.group(0)) {
        case "<p>":
          // <p> is simply removed.
          break;
        case "&amp;":
          result.append("&");
          break;
        case "&lt;":
          result.append("<");
          break;
        case "&gt;":
          result.append(">");
          break;
        case "&squot;":
          result.append("'");
          break;
        case "&quot;":
          result.append("\"");
          break;
        default:
          throw new IllegalArgumentException("Unrecognized HTML sequence: " + matcher.group(0));
      }
      pos = matcher.end();
    }

    // Add the string after the last HTML sequence.
    result.append(value.substring(pos));

    return result.toString().replace("\n ", "\n");
  }

  /**
   * Formats a list of words into a paragraph with less than maxWidth characters per line.
   */
  @VisibleForTesting
  static String formatParagraph(List<String> words, int maxWidth) {
    int lineLength = 0;

    StringBuilder output = new StringBuilder();
    for (String word : words) {
      // This check ensures that 1) don't add a space before the word and 2) always have at least
      // one word per line, so that we don't mishandle a very long word at the end of a line by
      // adding a blank line before the word.
      if (lineLength > 0) {
        // Do we have enough room for another word?
        if (lineLength + 1 + word.length() > maxWidth) {
          // No.  End the line.
          output.append('\n');
          lineLength = 0;
        } else {
          // Yes: Insert a space before the word.
          output.append(' ');
          ++lineLength;
        }
      }

      output.append(word);
      lineLength += word.length();
    }

    output.append('\n');
    return output.toString();
  }

  /**
   * Returns 'value' with words reflowed to maxWidth characters.
   */
  @VisibleForTesting
  static String reflow(String text, int maxWidth) {

    // A list of words that will be constructed into the list of words in a paragraph.
    ArrayList<String> words = new ArrayList<>();

    // Read through the lines, process a paragraph every time we get a blank line.
    StringBuilder resultBuilder = new StringBuilder();
    for (String line : Splitter.on('\n').trimResults().split(text)) {

      // If we got a blank line, format our current paragraph and start fresh.
      if (line.trim().isEmpty()) {
        resultBuilder.append(formatParagraph(words, maxWidth));
        resultBuilder.append('\n');
        words.clear();
        continue;
      }

      // Split the line into words and add them to the current paragraph.
      words.addAll(Splitter.on(
          CharMatcher.breakingWhitespace()).omitEmptyStrings().splitToList(line));
    }

    // Format the last paragraph, if any.
    if (!words.isEmpty()) {
      resultBuilder.append(formatParagraph(words, maxWidth));
    }

    return resultBuilder.toString();
  }

  /** Returns a string of HTML representing the provided flow documentation objects. */
  public static String generateMarkdownOutput(Iterable<FlowDocumentation> flowDocs) {
    StringBuilder output = new StringBuilder();
    output.append(MARKDOWN_HEADER);
    for (FlowDocumentation flowDoc : flowDocs) {
      output.append(String.format("## %s\n\n", flowDoc.getName()));
      output.append("### Description\n\n");
      output.append(String.format("%s\n\n", reflow(fixHtml(flowDoc.getClassDocs()), LINE_WIDTH)));
      output.append("### Errors\n\n");
      for (Long code : flowDoc.getErrorsByCode().keySet()) {
        output.append(String.format("*   %d\n", code));

        flowDoc.getErrorsByCode().get(code).stream()
            .map(ErrorCase::getReason)
            .distinct()
            .forEach(
                reason -> {
                  output.append("    *   ");
                  String wrappedReason = reflow(fixHtml(reason), LINE_WIDTH - 8);

                  // Replace internal newlines with indentation and strip the final newline.
                  output.append(wrappedReason.trim().replace("\n", "\n" + INDENT8));
                  output.append('\n');
                });
      }
      output.append('\n');
    }
    return output.toString();
  }

  private MarkdownDocumentationFormatter() {}
}
