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

import static google.registry.util.DateTimeUtils.START_INSTANT;

import google.registry.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
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

  /** Creates a FakeClock that starts at START_INSTANT. */
  public FakeClock() {
    this(START_INSTANT);
  }

  /** Creates a FakeClock initialized to a specific time. */
  @Deprecated
  public FakeClock(ReadableInstant startTime) {
    setTo(startTime);
  }

  /** Creates a FakeClock initialized to a specific time. */
  public FakeClock(Instant startTime) {
    setTo(startTime);
  }

  @Override
  public Instant now() {
    return Instant.ofEpochMilli(currentTimeMillis.addAndGet(autoIncrementStepMs));
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
  @Deprecated
  public FakeClock setAutoIncrementStep(ReadableDuration autoIncrementStep) {
    this.autoIncrementStepMs = autoIncrementStep.getMillis();
    return this;
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
  public FakeClock setAutoIncrementStep(Duration autoIncrementStep) {
    this.autoIncrementStepMs = autoIncrementStep.toMillis();
    return this;
  }

  /** Advances clock by one millisecond. */
  public void advanceOneMilli() {
    advanceBy(Duration.ofMillis(1));
  }

  /** Advances clock by some duration. */
  @Deprecated
  public void advanceBy(ReadableDuration duration) {
    currentTimeMillis.addAndGet(duration.getMillis());
  }

  /** Advances clock by some duration. */
  public void advanceBy(Duration duration) {
    currentTimeMillis.addAndGet(duration.toMillis());
  }

  /** Sets the time to the specified instant. */
  @Deprecated
  public void setTo(ReadableInstant time) {
    currentTimeMillis.set(time.getMillis());
  }

  /** Sets the time to the specified instant. */
  public void setTo(Instant time) {
    currentTimeMillis.set(time.toEpochMilli());
  }

  /** Invokes {@link #setAutoIncrementStep} with one millisecond-step. */
  public FakeClock setAutoIncrementByOneMilli() {
    return setAutoIncrementStep(Duration.ofMillis(1));
  }

  /** Disables the auto-increment mode. */
  public FakeClock disableAutoIncrement() {
    return setAutoIncrementStep(Duration.ZERO);
  }
}
