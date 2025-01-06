// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for {@link GetEppResourceCommand}. */
public class GetEppResourceCommandTest {

  private static final DateTime TEST_TIME = DateTime.now(DateTimeZone.UTC);

  private final FakeClock clock = new FakeClock(TEST_TIME);
  private GetEppResourceCommand commandUnderTest;

  @BeforeEach
  public void setup() {
    commandUnderTest = Mockito.spy(GetEppResourceCommand.class);
    commandUnderTest.clock = clock;
  }

  @Test
  public void readTimestampAfterNow_noException() {
    commandUnderTest.readTimestamp = clock.nowUtc().plusMillis(1);
    commandUnderTest.run();
  }

  @Test
  public void readTimestampBeforeNow_throwsException() {
    commandUnderTest.readTimestamp = clock.nowUtc().minusMillis(1);
    assertThrows(IllegalArgumentException.class, () -> commandUnderTest.run());
  }

  @Test
  public void readTimestampNotProvided_setToNow_noException() {
    commandUnderTest.run();
    assertThat(commandUnderTest.readTimestamp).isEqualTo(clock.nowUtc());
  }
}
