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

import java.security.SecureRandom;

/** Random string generator. */
public class RandomStringGenerator extends StringGenerator {

  private final SecureRandom random;

  public RandomStringGenerator(String alphabet, SecureRandom random) {
    super(alphabet);
    this.random = random;
  }

  /** Generates a random string of a specified length. */
  @Override
  public String createString(int length) {
    checkArgument(length > 0);
    char[] password = new char[length];
    for (int i = 0; i < length; ++i) {
      password[i] = alphabet.charAt(random.nextInt(alphabet.length()));
    }
    return new String(password);
  }
}
