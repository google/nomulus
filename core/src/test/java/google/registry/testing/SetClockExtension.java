// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Extension used for tests that a) set the clock to a specific time and b) make use of
 * ReplayExtension or any other test feature that requires writing to a commit log from test
 * utilities that aren't controlled by the ultimate test class (e.g. AppEngineExtension).
 */
public class SetClockExtension implements BeforeEachCallback {

  private FakeClock clock;
  private DateTime dateTime;

  /** Construct from a DateTime, that being the time to set the clock to. */
  public SetClockExtension(FakeClock clock, DateTime dateTime) {
    this.clock = clock;
    this.dateTime = dateTime;
  }

  /** Construct from a dateTime (the time to set the clock to) represented as a string. */
  public SetClockExtension(FakeClock clock, String dateTime) {
    this.clock = clock;
    this.dateTime = DateTime.parse(dateTime);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    clock.setTo(dateTime);
  }
}
