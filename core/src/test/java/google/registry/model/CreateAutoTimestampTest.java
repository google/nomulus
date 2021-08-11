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
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static org.joda.time.DateTimeZone.UTC;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.common.CrossTldSingleton;
import google.registry.model.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CreateAutoTimestamp}. */
@DualDatabaseTest
public class CreateAutoTimestampTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(CreateAutoTimestampTestObject.class)
          .withJpaUnitTestEntities(CreateAutoTimestampTestObject.class)
          .build();

  /** Timestamped class. */
  @Entity(name = "CatTestEntity")
  @EntityForTesting
  @javax.persistence.Entity
  public static class CreateAutoTimestampTestObject extends CrossTldSingleton {
    @Ignore @javax.persistence.Id long id = SINGLETON_ID;
    CreateAutoTimestamp createTime = CreateAutoTimestamp.create(null);
  }

  private CreateAutoTimestampTestObject reload() {
    return loadByEntity(new CreateAutoTimestampTestObject());
  }

  @TestOfyAndSql
  void testSaveSetsTime() {
    DateTime transactionTime =
        tm().transact(
                () -> {
                  CreateAutoTimestampTestObject object = new CreateAutoTimestampTestObject();
                  assertThat(object.createTime.getTimestamp()).isNull();
                  tm().put(object);
                  return tm().getTransactionTime();
                });
    tm().clearSessionCache();
    assertThat(reload().createTime.timestamp).isEqualTo(transactionTime);
  }

  @TestOfyAndSql
  void testResavingRespectsOriginalTime() {
    final DateTime oldCreateTime = DateTime.now(UTC).minusDays(1);
    tm().transact(
            () -> {
              CreateAutoTimestampTestObject object = new CreateAutoTimestampTestObject();
              object.createTime = CreateAutoTimestamp.create(oldCreateTime);
              tm().put(object);
            });
    tm().clearSessionCache();
    assertThat(reload().createTime.timestamp).isEqualTo(oldCreateTime);
  }
}
