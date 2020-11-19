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
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.Collection;

/** String generator. */
public abstract class StringGenerator implements Serializable {

  public static final int DEFAULT_PASSWORD_LENGTH = 16;

  /** A class containing different alphabets used to generate strings. */
  public static class Alphabets {

    /** A URL-safe Base64 alphabet (alphanumeric, hyphen, underscore). */
    public static final String BASE_64 =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-";

    /** An alphanumeric alphabet that omits visually similar characters. */
    public static final String BASE_58 =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** Digit-only alphabet. */
    public static final String DIGITS_ONLY = "0123456789";

    /** Full ASCII alphabet with a wide selection of punctuation characters. */
    public static final String FULL_ASCII =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`~!@#$%^&*()_-+={}[]\\/<>,.;?':| ";
  }

  protected String alphabet;

  protected StringGenerator(String alphabet) {
    checkArgument(!isNullOrEmpty(alphabet), "Alphabet cannot be null or empty.");
    this.alphabet = alphabet;
  }

  /** Generates a string of a specified length. */
  public abstract String createString(int length);

  /** Batch-generates an {@link ImmutableList} of strings of a specified length. */
  public Collection<String> createStrings(int length, int count) {
    ImmutableList.Builder<String> listBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < count; i++) {
      listBuilder.add(createString(length));
    }
    return listBuilder.build();
  }
}
