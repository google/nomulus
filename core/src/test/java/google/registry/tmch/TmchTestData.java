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

package google.registry.tmch;

import static com.google.common.base.CharMatcher.whitespace;
import static com.google.common.io.BaseEncoding.base64;

import com.google.common.io.ByteSource;
import google.registry.testing.TestDataHelper;

/** Utility class providing easy access to contents of the {@code testdata/} directory. */
public final class TmchTestData {

  private static final String BEGIN_ENCODED_SMD = "-----BEGIN ENCODED SMD-----";
  private static final String END_ENCODED_SMD = "-----END ENCODED SMD-----";

  /** Returns {@link ByteSource} for file in {@code tmch/testdata/} directory. */
  public static ByteSource loadBytes(String filename) {
    return TestDataHelper.loadBytes(TmchTestData.class, filename);
  }

  /** Loads data from file in {@code tmch/testdata/} as a String. */
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
}
