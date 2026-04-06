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
import static google.registry.util.DateTimeUtils.toDateTime;

import com.google.common.truth.ComparableSubject;
import com.google.common.truth.FailureMetadata;
import java.time.Instant;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Truth subject for {@link DateTime}.
 *
 * <p>This class facilitates easier temporal testing during the migration from Joda-Time to {@link
 * java.time}. It provides overloads for comparing {@link DateTime} instances against {@link
 * Instant} instances, reducing the need for manual conversions in test code.
 */
public final class DateTimeSubject extends ComparableSubject<DateTime> {

  private final DateTime actual;

  private DateTimeSubject(FailureMetadata failureMetadata, @Nullable DateTime actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  /** Asserts that the actual {@link DateTime} is equal to the expected {@link DateTime}. */
  public void isAt(@Nullable DateTime expected) {
    isEqualTo(expected);
  }

  /**
   * Asserts that the actual {@link DateTime} is equal to the expected {@link Instant}.
   *
   * <p>Conversion is handled via {@link google.registry.util.DateTimeUtils#toDateTime(Instant)}.
   */
  public void isAt(@Nullable Instant expected) {
    isEqualTo(toDateTime(expected));
  }

  /** Asserts that the actual {@link DateTime} is strictly before the expected {@link Instant}. */
  public void isBefore(@Nullable Instant expected) {
    isLessThan(toDateTime(expected));
  }

  /** Asserts that the actual {@link DateTime} is at or before the expected {@link DateTime}. */
  public void isAtOrBefore(@Nullable DateTime expected) {
    isAtMost(expected);
  }

  /** Asserts that the actual {@link DateTime} is at or before the expected {@link Instant}. */
  public void isAtOrBefore(@Nullable Instant expected) {
    isAtMost(toDateTime(expected));
  }

  /** Asserts that the actual {@link DateTime} is strictly after the expected {@link Instant}. */
  public void isAfter(@Nullable Instant expected) {
    isGreaterThan(toDateTime(expected));
  }

  /** Asserts that the actual {@link DateTime} is at or after the expected {@link DateTime}. */
  public void isAtOrAfter(@Nullable DateTime expected) {
    isAtLeast(expected);
  }

  /** Asserts that the actual {@link DateTime} is at or after the expected {@link Instant}. */
  public void isAtOrAfter(@Nullable Instant expected) {
    isAtLeast(toDateTime(expected));
  }

  /** Static entry point for creating a {@link DateTimeSubject} for the given actual value. */
  public static DateTimeSubject assertThat(@Nullable DateTime actual) {
    return assertAbout(DateTimeSubject::new).that(actual);
  }

  /** Internal factory for Truth. */
  static Factory<DateTimeSubject, DateTime> dateTimes() {
    return DateTimeSubject::new;
  }
}
