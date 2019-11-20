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
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import org.joda.time.ReadableDuration;

/** Implementation of {@link Sleeper} for production use. */
@ThreadSafe
public final class SystemSleeper implements Sleeper, Serializable {

  private static final long serialVersionUID = 2003215961965322843L;

  @Inject
  public SystemSleeper() {}

  @Override
  public void sleep(ReadableDuration duration) throws InterruptedException {
    checkArgument(duration.getMillis() >= 0);
    Thread.sleep(duration.getMillis());
  }

  @Override
  public void sleepUninterruptibly(ReadableDuration duration) {
    checkArgument(duration.getMillis() >= 0);
    Uninterruptibles.sleepUninterruptibly(duration.getMillis(), TimeUnit.MILLISECONDS);
  }
}
