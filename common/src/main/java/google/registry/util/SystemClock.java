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

import static org.joda.time.DateTimeZone.UTC;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Clock implementation that proxies to the real system clock. */
@ThreadSafe
public class SystemClock implements Clock {

  private static final long serialVersionUID = 5165372013848947515L;

  @Inject
  public SystemClock() {}

  /** Returns the current time. */
  @Override
  public DateTime nowUtc() {
    return DateTime.now(UTC);
  }
}
