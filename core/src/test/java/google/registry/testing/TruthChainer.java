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

package google.registry.testing;

import com.google.common.truth.Subject;

/** Shim classes to enable fluent chained assertions. */
public final class TruthChainer {

  /** Add another assertion. */
  public static class And<S extends Subject> {

    private final S subject;

    And(S subject) {
      this.subject = subject;
    }

    public S and() {
      return subject;
    }
  }

  /** Move the word "which" to after a parameterized assertion. */
  public static class Which<S extends Subject> {

    private final S subject;

    Which(S subject) {
      this.subject = subject;
    }

    public S which() {
      return subject;
    }
  }
}
