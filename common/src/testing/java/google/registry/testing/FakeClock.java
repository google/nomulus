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

package google.registry.testing;

import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.millis;

import google.registry.util.Clock;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.ReadableDuration;
import org.joda.time.ReadableInstant;

/** A mock clock for testing purposes that supports telling, setting, and advancing the time. */
@ThreadSafe
public final class FakeClock implements Clock {

  private static final long serialVersionUID = 675054721685304599L;

  // Clock isn't a thread synchronization primitive, but tests involving
  // threads should see a consistent flow.
  private final AtomicLong currentTimeMillis = new AtomicLong();

  private volatile long autoIncrementStepMs;

  /** Creates a FakeClock that starts at START_OF_TIME. */
  public FakeClock() {
    this(START_OF_TIME);
  }

  /** Creates a FakeClock initialized to a specific time. */
  public FakeClock(ReadableInstant startTime) {
    setTo(startTime);
  }

  /** Returns the current time. */
  @Override
  public DateTime nowUtc() {
    return new DateTime(currentTimeMillis.addAndGet(autoIncrementStepMs), UTC);
  }

  /**
   * Sets the increment applied to the clock whenever it is queried. The increment is zero by
   * default: the clock is left unchanged when queried.
   *
   * <p>Passing a duration of zero to this method effectively unsets the auto increment mode.
   *
   * @param autoIncrementStep the new auto increment duration
   * @return this
   */
  public FakeClock setAutoIncrementStep(ReadableDuration autoIncrementStep) {
    this.autoIncrementStepMs = autoIncrementStep.getMillis();
    return this;
  }

  /** Advances clock by one millisecond. */
  public void advanceOneMilli() {
    advanceBy(millis(1));
  }

  /** Advances clock by some duration. */
  public void advanceBy(ReadableDuration duration) {
    currentTimeMillis.addAndGet(duration.getMillis());
  }

  /** Sets the time to the specified instant. */
  public void setTo(ReadableInstant time) {
    currentTimeMillis.set(time.getMillis());
  }

  /** Invokes {@link #setAutoIncrementStep} with one millisecond-step. */
  public FakeClock setAutoIncrementByOneMilli() {
    return setAutoIncrementStep(Duration.millis(1));
  }

  /** Disables the auto-increment mode. */
  public FakeClock disableAutoIncrement() {
    return setAutoIncrementStep(Duration.ZERO);
  }
}
