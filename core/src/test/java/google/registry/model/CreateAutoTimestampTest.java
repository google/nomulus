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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.common.CrossTldSingleton;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CreateAutoTimestamp}. */
public class CreateAutoTimestampTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaUnitTestExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(CreateAutoTimestampTestObject.class)
          .buildUnitTestExtension();

  /** Timestamped class. */
  @Entity
  public static class CreateAutoTimestampTestObject extends CrossTldSingleton {
    @Id long id = SINGLETON_ID;
    CreateAutoTimestamp createTime = CreateAutoTimestamp.create(null);
  }

  private static CreateAutoTimestampTestObject reload() {
    return loadByEntity(new CreateAutoTimestampTestObject());
  }

  @Test
  void testSaveSetsTime() {
    DateTime transactionTime =
        tm().transact(
                () -> {
                  CreateAutoTimestampTestObject object = new CreateAutoTimestampTestObject();
                  assertThat(object.createTime.getTimestamp()).isNull();
                  tm().put(object);
                  return tm().getTransactionTime();
                });
    assertThat(reload().createTime.getTimestamp()).isEqualTo(transactionTime);
  }

  @Test
  void testResavingRespectsOriginalTime() {
    final DateTime oldCreateTime = DateTime.now(UTC).minusDays(1);
    tm().transact(
            () -> {
              CreateAutoTimestampTestObject object = new CreateAutoTimestampTestObject();
              object.createTime = CreateAutoTimestamp.create(oldCreateTime);
              tm().put(object);
            });
    assertThat(reload().createTime.getTimestamp()).isEqualTo(oldCreateTime);
  }
}
