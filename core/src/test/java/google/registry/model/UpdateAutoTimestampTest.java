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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_INSTANT;

import google.registry.model.common.CrossTldSingleton;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.testing.FakeClock;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateAutoTimestamp}. */
public class UpdateAutoTimestampTest {

  private final FakeClock clock = new FakeClock();

  @RegisterExtension
  public final JpaUnitTestExtension jpaUnitTestExtension =
      new JpaTestExtensions.Builder()
          .withClock(clock)
          .withEntityClass(UpdateAutoTimestampTestObject.class)
          .buildUnitTestExtension();

  /** Timestamped class. */
  @Entity
  public static class UpdateAutoTimestampTestObject extends CrossTldSingleton {
    @Id long id = SINGLETON_ID;
    UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create((Instant) null);
  }

  private static UpdateAutoTimestampTestObject reload() {
    return tm().transact(
            () -> tm().loadByKey(VKey.create(UpdateAutoTimestampTestObject.class, 1L)));
  }

  @Test
  void testSaveSetsTime() {
    Instant transactionTime =
        tm().transact(
                () -> {
                  clock.advanceOneMilli();
                  UpdateAutoTimestampTestObject object = new UpdateAutoTimestampTestObject();
                  assertThat(object.updateTime.getTimestamp()).isEqualTo(START_INSTANT);
                  tm().insert(object);
                  return tm().getTxTime();
                });
    assertThat(reload().updateTime.getTimestamp()).isEqualTo(transactionTime);
  }

  @Test
  void testResavingOverwritesOriginalTime() {
    Instant transactionTime =
        tm().transact(
                () -> {
                  clock.advanceOneMilli();
                  UpdateAutoTimestampTestObject object = new UpdateAutoTimestampTestObject();
                  object.updateTime =
                      UpdateAutoTimestamp.create(clock.now().minus(Duration.ofDays(1)));
                  tm().insert(object);
                  return tm().getTxTime();
                });
    assertThat(reload().updateTime.getTimestamp()).isEqualTo(transactionTime);
  }

  @Test
  void testReadingTwiceDoesNotModify() {
    Instant originalTime = Instant.parse("1999-01-01T00:00:00Z");
    clock.setTo(originalTime);
    tm().transact(() -> tm().insert(new UpdateAutoTimestampTestObject()));
    clock.advanceOneMilli();
    UpdateAutoTimestampTestObject firstRead = reload();
    assertThat(firstRead.updateTime.getTimestamp()).isEqualTo(originalTime);
    clock.advanceOneMilli();
    UpdateAutoTimestampTestObject secondRead = reload();
    assertThat(secondRead.updateTime.getTimestamp()).isEqualTo(originalTime);
  }
}
