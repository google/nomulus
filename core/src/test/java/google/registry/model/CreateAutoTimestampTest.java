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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;

import com.googlecode.objectify.annotation.Entity;
import google.registry.model.common.CrossTldSingleton;
import google.registry.testing.AppEngineRule;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CreateAutoTimestamp}. */
@RunWith(JUnit4.class)
public class CreateAutoTimestampTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  /** Timestamped class. */
  @Entity(name = "CatTestEntity")
  public static class TestObject extends CrossTldSingleton {
    CreateAutoTimestamp createTime = CreateAutoTimestamp.create(null);
  }

  private TestObject reload() {
    return ofy().load().entity(new TestObject()).now();
  }

  @Test
  public void testSaveSetsTime() {
    DateTime transactionTime =
        tm()
            .transact(
                () -> {
                  TestObject object = new TestObject();
                  assertThat(object.createTime.getTimestamp()).isNull();
                  ofy().save().entity(object);
                  return tm().getTransactionTime();
                });
    ofy().clearSessionCache();
    assertThat(reload().createTime.timestamp).isEqualTo(transactionTime);
  }

  @Test
  public void testResavingRespectsOriginalTime() {
    final DateTime oldCreateTime = DateTime.now(UTC).minusDays(1);
    tm()
        .transact(
            () -> {
              TestObject object = new TestObject();
              object.createTime = CreateAutoTimestamp.create(oldCreateTime);
              ofy().save().entity(object);
            });
    ofy().clearSessionCache();
    assertThat(reload().createTime.timestamp).isEqualTo(oldCreateTime);
  }
}
