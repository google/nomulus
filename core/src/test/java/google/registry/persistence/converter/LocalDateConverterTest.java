// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link LocalDateConverter}. */
public class LocalDateConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(LocalDateConverterTestEntity.class)
          .buildUnitTestExtension();

  private final LocalDate exampleDate = LocalDate.parse("2020-06-10", ISODateTimeFormat.date());

  @Test
  void testNullInput() {
    LocalDateConverterTestEntity retrievedEntity = persistAndLoadTestEntity(null);
    assertThat(retrievedEntity.date).isNull();
  }

  @Test
  void testSaveAndLoad_success() {
    LocalDateConverterTestEntity retrievedEntity = persistAndLoadTestEntity(exampleDate);
    assertThat(retrievedEntity.date).isEqualTo(exampleDate);
  }

  private LocalDateConverterTestEntity persistAndLoadTestEntity(LocalDate date) {
    LocalDateConverterTestEntity entity = new LocalDateConverterTestEntity(date);
    persistResource(entity);
    return tm().transact(
            () -> tm().loadByKey(VKey.create(LocalDateConverterTestEntity.class, "id")));
  }

  /** Override entity name to avoid the nested class reference. */
  @Entity(name = "LocalDateConverterTestEntity")
  private static class LocalDateConverterTestEntity extends ImmutableObject {

    @Id String name = "id";

    LocalDate date;

    public LocalDateConverterTestEntity() {}

    LocalDateConverterTestEntity(LocalDate date) {
      this.date = date;
    }
  }
}
