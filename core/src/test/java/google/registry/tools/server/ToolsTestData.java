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

package google.registry.tools.server;

import com.google.common.io.ByteSource;
import google.registry.testing.TestDataHelper;
import java.util.Map;

/** Utility class providing easy access to contents of the {@code testdata/} directory. */
public final class ToolsTestData {

  /** Returns {@link ByteSource} for file in {@code tools/server/testdata/} directory. */
  public static ByteSource loadBytes(String filename) {
    return TestDataHelper.loadBytes(ToolsTestData.class, filename);
  }

  /**
   * Loads data from file in {@code tools/server/testdata/} as a UTF-8 String.
   */
  public static String loadFile(String filename) {
    return TestDataHelper.loadFile(ToolsTestData.class, filename);
  }

  /**
   * Loads data from file in {@code tools/server/testdata/} as a UTF-8 String, with substitutions.
   */
  public static String loadFile(String filename, Map<String, String> substitutions) {
    return TestDataHelper.loadFile(ToolsTestData.class, filename, substitutions);
  }
}
