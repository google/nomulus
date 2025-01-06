// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Random string generator. */
public class RandomStringGenerator extends StringGenerator {

  private final Supplier<Random> random;

  public RandomStringGenerator(String alphabet, SecureRandom random) {
    this(alphabet, () -> random);
  }

  private RandomStringGenerator(String alphabet, Supplier<Random> maybeInsecure) {
    super(alphabet);
    this.random = maybeInsecure;
  }

  /** Generates a random string of a specified length. */
  @Override
  public String createString(int length) {
    checkArgument(length > 0);
    char[] password = new char[length];
    for (int i = 0; i < length; ++i) {
      password[i] = alphabet.charAt(random.get().nextInt(alphabet.length()));
    }
    return new String(password);
  }

  /**
   * Returns an instance of this class backed by an insecure {@link Random random number generator}.
   *
   * <p>This is good for generating non-critical data at high throughput, e.g., log traces.
   */
  public static RandomStringGenerator insecureRandomStringGenerator(String alphabet) {
    // Use the low-contention ThreadLocalRandom. Must call `current` at every use.
    return new RandomStringGenerator(alphabet, ThreadLocalRandom::current);
  }
}
