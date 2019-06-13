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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.charactersOf;

import com.google.common.collect.Iterators;
import google.registry.util.RandomStringGenerator;
import google.registry.util.StringGenerator;
import java.util.Iterator;
import javax.inject.Named;

/**
 * A string generator that produces strings using sequential characters in its alphabet. This is
 * most useful in tests as a "fake" password generator (which would otherwise use
 * {@link RandomStringGenerator}.
 *
 * <p>Note that consecutive calls to createString will continue where the last call left off in
 * the alphabet.
 */
public class DeterministicStringGenerator extends StringGenerator {

  private Iterator<Character> iterator;
  private final Rule rule;
  private int counter = 0;

  /** String generation rules. */
  public enum Rule {

    /**
     * Simple string generation, cycling through sequential letters in the alphabet. May produce
     * duplicates.
     */
    DEFAULT,

    /**
     * Same cyclical pattern as {@link Rule#DEFAULT}, prepending the iteration number and an
     * underscore. Intended to avoid duplicates.
     */
    PREPEND_COUNTER
  }

  /**
   * Generates a string using sequential characters in the generator's alphabet, cycling back to the
   * beginning of the alphabet if necessary.
   */
  @Override
  public String createString(int length) {
    checkArgument(length > 0, "String length must be positive.");
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < length; i++) {
      password.append(iterator.next());
    }
    switch (rule) {
      case PREPEND_COUNTER:
        return String.format("%04d_%s", counter++, password.toString());
      case DEFAULT:
      default:
        return password.toString();
    }
  }

  public DeterministicStringGenerator(@Named("alphabetBase64") String alphabet, Rule rule) {
    super(alphabet);
    iterator = Iterators.cycle(charactersOf(alphabet));
    this.rule = rule;
  }

  public DeterministicStringGenerator(@Named("alphabetBase64") String alphabet) {
    this(alphabet, Rule.DEFAULT);
  }
}
