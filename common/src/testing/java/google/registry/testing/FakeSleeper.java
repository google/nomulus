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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import google.registry.util.Sleeper;
import java.io.Serializable;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.ReadableDuration;

/** Sleeper implementation for unit tests that advances {@link FakeClock} rather than sleep. */
@ThreadSafe
public final class FakeSleeper implements Sleeper, Serializable {

  private static final long serialVersionUID = -8975804222581077291L;

  private final FakeClock clock;

  public FakeSleeper(FakeClock clock) {
    this.clock = checkNotNull(clock, "clock");
  }

  @Override
  public void sleep(ReadableDuration duration) throws InterruptedException {
    checkArgument(duration.getMillis() >= 0);
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    clock.advanceBy(duration);
  }

  @Override
  public void sleepUninterruptibly(ReadableDuration duration) {
    checkArgument(duration.getMillis() >= 0);
    clock.advanceBy(duration);
  }
}
