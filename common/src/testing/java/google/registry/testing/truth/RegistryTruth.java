// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing.truth;

import java.time.Instant;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Central entry point for Nomulus-specific Truth subjects.
 *
 * <p>This class provides convenient methods for performing temporal assertions in tests, especially
 * during the migration from Joda-Time to {@link java.time}.
 *
 * <p>Usage: {@code import static google.registry.testing.truth.RegistryTruth.assertAt;}
 */
public final class RegistryTruth {

  /** Returns a {@link InstantSubject} for the given {@link Instant}. */
  public static InstantSubject assertAt(@Nullable Instant actual) {
    return InstantSubject.assertThat(actual);
  }

  /** Returns a {@link DateTimeSubject} for the given {@link DateTime}. */
  public static DateTimeSubject assertAt(@Nullable DateTime actual) {
    return DateTimeSubject.assertThat(actual);
  }

  private RegistryTruth() {}
}
