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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.TimedTransitionProperty.forMapify;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;
import java.util.Set;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TimedTransitionProperty}. */
@RunWith(JUnit4.class)
public class TimedTransitionPropertyTest {
  private static final DateTime A_LONG_TIME_AGO = new DateTime(Long.MIN_VALUE, UTC);

  private static final DateTime DATE_1 = DateTime.parse("2001-01-01T00:00:00.0Z");
  private static final DateTime DATE_2 = DateTime.parse("2002-01-01T00:00:00.0Z");
  private static final DateTime DATE_3 = DateTime.parse("2003-01-01T00:00:00.0Z");

  // Simple implementation of TimedTransition for testing.  Public so it can be instantiated via
  // reflection.
  @VisibleForTesting
  public static class StringTimedTransition
      extends TimedTransitionProperty.TimedTransition<String> {
    private String value;

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public void setValue(String value) {
      this.value = value;
    }
  }

  private static final ImmutableSortedMap<DateTime, String> values = ImmutableSortedMap.of(
      START_OF_TIME, "0",
      DATE_1, "1",
      DATE_2, "2",
      DATE_3, "3");

  TimedTransitionProperty<String, StringTimedTransition> timedString;

  @Before
  public void init() {
    timedString = TimedTransitionProperty.fromValueMap(values, StringTimedTransition.class);
  }

  @Test
  public void testSuccess_toValueMap() {
    assertThat(timedString.toValueMap()).isEqualTo(values);
  }

  private static void testGetValueAtTime(
      TimedTransitionProperty<String, StringTimedTransition> timedString) {
    assertThat(timedString.getValueAtTime(A_LONG_TIME_AGO)).isEqualTo("0");
    assertThat(timedString.getValueAtTime(START_OF_TIME.minusMillis(1))).isEqualTo("0");
    assertThat(timedString.getValueAtTime(START_OF_TIME)).isEqualTo("0");
    assertThat(timedString.getValueAtTime(DATE_1.minusMillis(1))).isEqualTo("0");
    assertThat(timedString.getValueAtTime(DATE_1)).isEqualTo("1");
    assertThat(timedString.getValueAtTime(DATE_1.plusMillis(1))).isEqualTo("1");
    assertThat(timedString.getValueAtTime(DATE_2.minusMillis(1))).isEqualTo("1");
    assertThat(timedString.getValueAtTime(DATE_2)).isEqualTo("2");
    assertThat(timedString.getValueAtTime(DATE_2.plusMillis(1))).isEqualTo("2");
    assertThat(timedString.getValueAtTime(DATE_3.minusMillis(1))).isEqualTo("2");
    assertThat(timedString.getValueAtTime(DATE_3)).isEqualTo("3");
    assertThat(timedString.getValueAtTime(DATE_3.plusMillis(1))).isEqualTo("3");
    assertThat(timedString.getValueAtTime(END_OF_TIME)).isEqualTo("3");
  }

  @Test
  public void testSuccess_getValueAtTime() {
    testGetValueAtTime(timedString);
  }

  @Test
  public void testSuccess_getNextTransitionAfter() {
    assertThat(timedString.getNextTransitionAfter(A_LONG_TIME_AGO)).isEqualTo(DATE_1);
    assertThat(timedString.getNextTransitionAfter(START_OF_TIME.plusMillis(1))).isEqualTo(DATE_1);
    assertThat(timedString.getNextTransitionAfter(DATE_1.minusMillis(1))).isEqualTo(DATE_1);
    assertThat(timedString.getNextTransitionAfter(DATE_1)).isEqualTo(DATE_2);
    assertThat(timedString.getNextTransitionAfter(DATE_2.minusMillis(1))).isEqualTo(DATE_2);
    assertThat(timedString.getNextTransitionAfter(DATE_2)).isEqualTo(DATE_3);
    assertThat(timedString.getNextTransitionAfter(DATE_3)).isNull();
  }

  @Test
  public void testSuccess_simulatedLoad() {
    // Just for testing, don't extract transitions from a TimedTransitionProperty in real code.
    Set<Map.Entry<DateTime, StringTimedTransition>> transitions = timedString.entrySet();
    timedString = forMapify("0", StringTimedTransition.class);
    // Simulate a load from Datastore by clearing and then re-inserting the original transitions.
    timedString.clear();
    for (Map.Entry<DateTime, StringTimedTransition> transition : transitions) {
      timedString.put(transition.getKey(), transition.getValue());
    }
    timedString.checkValidity();
    testGetValueAtTime(timedString);
  }

  @Test
  public void testFailure_valueMapNotChronologicallyOrdered() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.<DateTime, String>reverseOrder().put(START_OF_TIME, "0").build(),
                StringTimedTransition.class));
  }

  @Test
  public void testFailure_transitionTimeBeforeStartOfTime() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(A_LONG_TIME_AGO, "?"), StringTimedTransition.class));
  }

  @Test
  public void testFailure_noValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(), StringTimedTransition.class));
  }

  @Test
  public void testFailure_noValueAtStartOfTime() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(DATE_1, "1"), StringTimedTransition.class));
  }

  @Test
  public void testFailure_noValuesAfterSimulatedEmptyLoad() {
    timedString = forMapify("0", StringTimedTransition.class);
    // Simulate a load from Datastore by clearing, but don't insert any transitions.
    timedString.clear();
    assertThrows(IllegalStateException.class, timedString::checkValidity);
  }

  @Test
  public void testFailure_noValueAtStartOfTimeAfterSimulatedLoad() {
    // Just for testing, don't extract transitions from a TimedTransitionProperty in real code.
    StringTimedTransition transition1 = timedString.get(DATE_1);
    timedString = forMapify("0", StringTimedTransition.class);
    // Simulate a load from Datastore by clearing and inserting transitions, but deliberately
    // omit a transition corresponding to START_OF_TIME.
    timedString.clear();
    timedString.put(DATE_1, transition1);
    assertThrows(IllegalStateException.class, timedString::checkValidity);
  }
}
