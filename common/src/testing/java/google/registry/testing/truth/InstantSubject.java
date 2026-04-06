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

import static com.google.common.truth.Truth.assertAbout;
import static google.registry.util.DateTimeUtils.toInstant;

import com.google.common.truth.ComparableSubject;
import com.google.common.truth.FailureMetadata;
import java.time.Instant;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Truth subject for {@link Instant}.
 *
 * <p>This class facilitates easier temporal testing during the migration from Joda-Time to {@link
 * java.time}. It provides overloads for comparing {@link Instant} instances against {@link
 * DateTime} instances, reducing the need for manual conversions in test code.
 */
public final class InstantSubject extends ComparableSubject<Instant> {

  private final Instant actual;

  private InstantSubject(FailureMetadata failureMetadata, @Nullable Instant actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  /** Asserts that the actual {@link Instant} is equal to the expected {@link Instant}. */
  public void isAt(@Nullable Instant expected) {
    isEqualTo(expected);
  }

  /**
   * Asserts that the actual {@link Instant} is equal to the expected {@link DateTime}.
   *
   * <p>Conversion is handled via {@link google.registry.util.DateTimeUtils#toInstant(DateTime)}.
   */
  public void isAt(@Nullable DateTime expected) {
    isEqualTo(toInstant(expected));
  }

  /** Asserts that the actual {@link Instant} is strictly before the expected {@link DateTime}. */
  public void isBefore(@Nullable DateTime expected) {
    isLessThan(toInstant(expected));
  }

  /** Asserts that the actual {@link Instant} is at or before the expected {@link Instant}. */
  public void isAtOrBefore(@Nullable Instant expected) {
    isAtMost(expected);
  }

  /** Asserts that the actual {@link Instant} is at or before the expected {@link DateTime}. */
  public void isAtOrBefore(@Nullable DateTime expected) {
    isAtMost(toInstant(expected));
  }

  /** Asserts that the actual {@link Instant} is strictly after the expected {@link DateTime}. */
  public void isAfter(@Nullable DateTime expected) {
    isGreaterThan(toInstant(expected));
  }

  /** Asserts that the actual {@link Instant} is at or after the expected {@link Instant}. */
  public void isAtOrAfter(@Nullable Instant expected) {
    isAtLeast(expected);
  }

  /** Asserts that the actual {@link Instant} is at or after the expected {@link DateTime}. */
  public void isAtOrAfter(@Nullable DateTime expected) {
    isAtLeast(toInstant(expected));
  }

  /** Static entry point for creating an {@link InstantSubject} for the given actual value. */
  public static InstantSubject assertThat(@Nullable Instant actual) {
    return assertAbout(InstantSubject::new).that(actual);
  }

  /** Internal factory for Truth. */
  static Factory<InstantSubject, Instant> instants() {
    return InstantSubject::new;
  }
}
