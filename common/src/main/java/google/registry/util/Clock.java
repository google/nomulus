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
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.DateTime;

/**
 * A clock that tells the current time in milliseconds or nanoseconds.
 *
 * <p>Clocks are technically serializable because they are either a stateless wrapper around the
 * system clock, or for testing, are just a wrapper around a DateTime. This means that if you
 * serialize a clock and deserialize it elsewhere, you won't necessarily get the same time or time
 * zone -- what you will get is a functioning clock.
 */
@ThreadSafe
public interface Clock extends Serializable {

  /** Returns current time in UTC timezone. */
  DateTime nowUtc();
}
