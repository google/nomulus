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

package google.registry.tmch;

import static com.google.common.base.CharMatcher.whitespace;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.tmch.TmchData.BEGIN_ENCODED_SMD;
import static google.registry.tmch.TmchData.END_ENCODED_SMD;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.testing.TestDataHelper;

/**
 * Utility class providing easy access to contents of the {@code
 * core/src/test/resources/google/registry/tmch/} directory.
 */
public final class TmchTestData {

  private TmchTestData() {}

  /**
   * Returns {@link ByteSource} for file in {@code core/src/test/resources/google/registry/tmch/}
   * directory.
   */
  public static ByteSource loadBytes(String filename) {
    return TestDataHelper.loadBytes(TmchTestData.class, filename);
  }

  /** Loads data from file in {@code core/src/test/resources/google/registry/tmch/} as a String. */
  public static String loadFile(String filename) {
    return TestDataHelper.loadFile(TmchTestData.class, filename);
  }

  /** Extracts SMD XML from an ASCII-armored file. */
  static byte[] loadSmd(String file) {
    String data = loadFile(file);
    return base64()
        .decode(
            whitespace()
                .removeFrom(
                    data.substring(
                        data.indexOf(BEGIN_ENCODED_SMD) + BEGIN_ENCODED_SMD.length(),
                        data.indexOf(END_ENCODED_SMD))));
  }

  public static ImmutableList<String> extractLabels(String filepath) {
    String pattern = "U-labels: ";
    String content = loadFile(filepath);
    Matcher matcher = Pattern.compile(pattern + ".*").matcher(content);
    matcher.find();
    String matchedString = matcher.group().replace(pattern, "");
    return matchedString.isEmpty()
        ? ImmutableList.of()
        : ImmutableList.copyOf(Splitter.on(',').trimResults().split(matchedString));
  }
}
