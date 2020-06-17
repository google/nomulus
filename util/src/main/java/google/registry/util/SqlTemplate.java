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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.difference;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.concurrent.Immutable;

/** SQL template variable substitution. */
@Immutable
public final class SqlTemplate {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][_A-Z0-9]*");

  private static final Pattern SEARCH_PATTERN =
      Pattern.compile("(['\"]?)%(" + KEY_PATTERN + ")%(['\"]?)");

  private static final CharMatcher LEGAL_SUBSTITUTIONS =
      JavaCharMatchers.asciiLetterOrDigitMatcher().or(CharMatcher.anyOf("-_.,: "));

  /** Returns a new immutable SQL template builder object, for query parameter substitution. */
  public static SqlTemplate create(String template) {
    return new SqlTemplate(template, ImmutableMap.of());
  }

  /**
   * Adds a key/value that should be substituted an individual variable in the template.
   *
   * <p>Your template variables should appear as follows: {@code WHERE foo = '%BAR%'} and you would
   * call {@code .put("BAR", "some value"} to safely substitute it with a value. Only allow-listed
   * characters (as defined by {@link #LEGAL_SUBSTITUTIONS}) are allowed in values.
   *
   * @param key uppercase string that can have digits and underscores
   * @param value substitution value, composed of allow-listed characters
   * @throws IllegalArgumentException if key or value has bad chars or duplicate keys were added
   */
  public SqlTemplate put(String key, String value) {
    checkArgument(KEY_PATTERN.matcher(key).matches(), "Bad substitution key: %s", key);
    checkArgument(LEGAL_SUBSTITUTIONS.matchesAllOf(value), "Illegal characters in %s", value);
    return new SqlTemplate(template, new ImmutableMap.Builder<String, String>()
        .putAll(substitutions)
        .put(key, value)
        .build());
  }

  /**
   * Returns the freshly substituted SQL code.
   *
   * @throws IllegalArgumentException if any substitution variable is not found in the template,
   *         or if there are any variable-like strings (%something%) left after substitution.
   */
  public String build() {
    StringBuffer result = new StringBuffer(template.length());
    Set<String> found = new HashSet<>();
    Matcher matcher = SEARCH_PATTERN.matcher(template);
    while (matcher.find()) {
      String wholeMatch = matcher.group(0);
      String leftQuote = matcher.group(1);
      String key = matcher.group(2);
      String rightQuote = matcher.group(3);
      String value = substitutions.get(key);
      checkArgumentNotNull(value, "%%s% found in template but no substitution specified", key);
      checkArgument(leftQuote.equals(rightQuote), "Quote mismatch: %s", wholeMatch);
      matcher.appendReplacement(result, String.format("%s%s%s", leftQuote, value, rightQuote));
      found.add(key);
    }
    matcher.appendTail(result);
    Set<String> remaining = difference(substitutions.keySet(), found);
    checkArgument(remaining.isEmpty(),
        "Not found in template: %s", Joiner.on(", ").join(remaining));
    return result.toString();
  }

  private final String template;
  private final ImmutableMap<String, String> substitutions;

  private SqlTemplate(String template, ImmutableMap<String, String> substitutions) {
    this.template = checkNotNull(template);
    this.substitutions = checkNotNull(substitutions);
  }
}
