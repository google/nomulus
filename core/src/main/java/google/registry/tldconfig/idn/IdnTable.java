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

package google.registry.tldconfig.idn;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.errorprone.annotations.Immutable;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.net.URI;
import java.util.Optional;

/** An IDN table for a particular TLD. */
@Immutable
public final class IdnTable {

  /** Regular expression to match a line of an IDN table. */
  private static final Pattern LINE_PATTERN = Pattern.compile("^U\\+([0-9a-fA-F]{4,6})");

  private static final String URL_LINE_PREFIX = "# URL: ";
  private static final String POLICY_LINE_PREFIX = "# Policy: ";

  /** Language name for this table (corresponds to filename.) */
  private final String name;

  /**
   * Public URL of this IDN table, which is needed by RDE.
   *
   * @see <a href="https://tools.ietf.org/html/draft-arias-noguchi-dnrd-objects-mapping-05#section-5.5.1.1">
   *     DNRD Objects Mapping - &ltrdeIDN:idnTableRef&gt object</a>
   */
  private final URI url;

  /** Public URL of policy for this IDN table, which is needed by RDE. */
  private final URI policy;

  /** {@link ImmutableRangeSet} containing the valid codepoints in this table. */
  private final ImmutableRangeSet<Integer> validCodepoints;

  /** Validates the language rules associated with this IDN table. */
  private final Optional<LanguageValidator> languageValidator;

  private IdnTable(
      String name,
      URI url,
      URI policy,
      ImmutableRangeSet<Integer> validCodepoints,
      Optional<LanguageValidator> languageValidator) {
    this.name = name;
    this.url = checkNotNull(url, "%s missing '# URL: http://foo.example/page' line", name);
    this.policy = checkNotNull(policy, "%s missing '# Policy: http://foo.example/page' line", name);
    this.validCodepoints = checkNotNull(validCodepoints);
    this.languageValidator = languageValidator;
  }

  public String getName() {
    return name;
  }

  public URI getUrl() {
    return url;
  }

  public URI getPolicy() {
    return policy;
  }

  /**
   * Returns true if the given label is valid for this IDN table. A label is considered valid if all
   * of its codepoints are in the IDN table.
   */
  boolean isValidLabel(String label) {
    final int length = label.length();
    for (int i = 0; i < length; ) {
      int codepoint = label.codePointAt(i);
      if (!validCodepoints.contains(codepoint)) {
        return false;
      }

      // Some codepoints take up more than one character in Java strings (e.g. high and low
      // surrogates).
      i += Character.charCount(codepoint);
    }
    return !(languageValidator.isPresent()
        && !languageValidator.get().isValidLabelForLanguage(label));
  }

  /** Creates an IDN table given the lines from text file. */
  static IdnTable createFrom(
      String language, Iterable<String> data, Optional<LanguageValidator> languageValidator) {
    ImmutableRangeSet.Builder<Integer> rangeSet = new ImmutableRangeSet.Builder<>();
    URI url = null;
    URI policy = null;
    for (String line : data) {
      // Remove leading and trailing whitespace.
      line = line.trim();

      // Handle special comment lines.
      if (line.startsWith(URL_LINE_PREFIX)) {
        url = URI.create(line.substring(URL_LINE_PREFIX.length()));
      } else if (line.startsWith(POLICY_LINE_PREFIX)) {
        policy = URI.create(line.substring(POLICY_LINE_PREFIX.length()));
      }

      // Skip empty and comment lines.
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }

      int codepoint = readCodepoint(line);
      rangeSet.add(Range.singleton(codepoint));
    }
    return new IdnTable(language, url, policy, rangeSet.build(), languageValidator);
  }

  /**
   * Read the codepoint from a single line. The expected format of each line is:
   * {@code U+XXXX}
   * Where {@code XXXX} holds the hex value of the codepoint.
   */
  private static int readCodepoint(String line) {
    Matcher matcher = LINE_PATTERN.matcher(line);
    checkArgument(matcher.lookingAt(), "Can't parse line: %s", line);

    String hexString = matcher.group(1);
    return Integer.parseInt(hexString, 16);
  }
}
