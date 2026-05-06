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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.util.concurrent.Uninterruptibles;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.time.Duration;
import javax.annotation.concurrent.ThreadSafe;

/** Implementation of {@link Sleeper} for production use. */
@ThreadSafe
public final class SystemSleeper implements Sleeper, Serializable {

  private static final long serialVersionUID = 2003215961965322843L;

  @Inject
  public SystemSleeper() {}

  @Override
  public void sleep(Duration duration) throws InterruptedException {
    checkArgument(!duration.isNegative(), "Duration must be non-negative");
    Thread.sleep(duration.toMillis());
  }

  @Override
  public void sleepUninterruptibly(Duration duration) {
    checkArgument(!duration.isNegative(), "Duration must be non-negative");
    Uninterruptibles.sleepUninterruptibly(duration);
  }
}
