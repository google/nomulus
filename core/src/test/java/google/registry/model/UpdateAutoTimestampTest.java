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
import static org.joda.time.DateTimeZone.UTC;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.common.CrossTldSingleton;
import google.registry.model.ofy.Ofy;
import google.registry.persistence.VKey;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdateAutoTimestamp}. */
@DualDatabaseTest
public class UpdateAutoTimestampTest {

  FakeClock clock = new FakeClock();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withJpaUnitTestEntities(UpdateAutoTimestampTestObject.class)
          .withOfyTestEntities(UpdateAutoTimestampTestObject.class)
          .withClock(clock)
          .build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  /** Timestamped class. */
  @Entity(name = "UatTestEntity")
  @javax.persistence.Entity
  @EntityForTesting
  public static class UpdateAutoTimestampTestObject extends CrossTldSingleton {
    @Ignore @javax.persistence.Id long id = SINGLETON_ID;
    UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create(null);
  }

  private UpdateAutoTimestampTestObject reload() {
    return tm().transact(
            () ->
                tm().loadByKey(
                        VKey.create(
                            UpdateAutoTimestampTestObject.class,
                            1L,
                            Key.create(new UpdateAutoTimestampTestObject()))));
  }

  @TestOfyAndSql
  void testSaveSetsTime() {
    DateTime transactionTime =
        tm().transact(
                () -> {
                  clock.advanceOneMilli();
                  UpdateAutoTimestampTestObject object = new UpdateAutoTimestampTestObject();
                  assertThat(object.updateTime.timestamp).isNull();
                  tm().insert(object);
                  return tm().getTransactionTime();
                });
    tm().clearSessionCache();
    assertThat(reload().updateTime.timestamp).isEqualTo(transactionTime);
  }

  @TestOfyAndSql
  void testDisabledUpdates() throws Exception {
    DateTime initialTime =
        tm().transact(
                () -> {
                  clock.advanceOneMilli();
                  tm().insert(new UpdateAutoTimestampTestObject());
                  return tm().getTransactionTime();
                });

    UpdateAutoTimestampTestObject object = reload();
    clock.advanceOneMilli();

    try (UpdateAutoTimestamp.DisableAutoUpdateResource disabler =
        new UpdateAutoTimestamp.DisableAutoUpdateResource()) {
      DateTime secondTransactionTime =
          tm().transact(
                  () -> {
                    tm().put(object);
                    return tm().getTransactionTime();
                  });
      assertThat(secondTransactionTime).isGreaterThan(initialTime);
    }
    assertThat(reload().updateTime.timestamp).isEqualTo(initialTime);
  }

  @TestOfyAndSql
  void testResavingOverwritesOriginalTime() {
    DateTime transactionTime =
        tm().transact(
                () -> {
                  clock.advanceOneMilli();
                  UpdateAutoTimestampTestObject object = new UpdateAutoTimestampTestObject();
                  object.updateTime = UpdateAutoTimestamp.create(DateTime.now(UTC).minusDays(1));
                  tm().insert(object);
                  return tm().getTransactionTime();
                });
    tm().clearSessionCache();
    assertThat(reload().updateTime.timestamp).isEqualTo(transactionTime);
  }

  @TestOfyAndSql
  void testReadingTwiceDoesNotModify() {
    DateTime originalTime = DateTime.parse("1999-01-01T00:00:00Z");
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
