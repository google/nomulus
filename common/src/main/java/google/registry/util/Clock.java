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

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A clock that tells the current time in milliseconds or nanoseconds.
 *
 * <p>Clocks are technically serializable because they are either a stateless wrapper around the
 * system clock, or for testing, are just a wrapper around an Instant. This means that if you
 * serialize a clock and deserialize it elsewhere, you won't necessarily get the same time or time
 * zone -- what you will get is a functioning clock.
 */
@ThreadSafe
public interface Clock extends Serializable {

  /** Returns current Instant (which is always in UTC). */
  Instant now();

  /** Returns the current time as an {@link OffsetDateTime} in UTC. */
  default OffsetDateTime nowDateTime() {
    return OffsetDateTime.ofInstant(now(), ZoneOffset.UTC);
  }

  /** Returns the current time as a {@link LocalDate} in UTC. */
  default LocalDate nowDate() {
    return LocalDate.ofInstant(now(), ZoneOffset.UTC);
  }

  /** Returns the current time in milliseconds since the epoch. */
  default long nowMillis() {
    return now().toEpochMilli();
  }
}
