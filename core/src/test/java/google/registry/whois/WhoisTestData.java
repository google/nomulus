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

package google.registry.whois;

import google.registry.testing.TestDataHelper;

/** Test helper methods for the whois package. */
final class WhoisTestData {

  /**
   * Loads test data from file in {@code testdata/} directory, "fixing" newlines to have the ending
   * that WHOIS requires.
   */
  static String loadFile(String filename) {
    return TestDataHelper.loadFile(WhoisTestData.class, filename).replaceAll("\r?\n", "\r\n");
  }
}
