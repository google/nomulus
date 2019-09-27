// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.tmch.ClaimsList;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ZonedDateTimeConverter}. */
@RunWith(JUnit4.class)
public class ZonedDateTimeConverterTest {

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  private final ZonedDateTimeConverter converter = new ZonedDateTimeConverter();

  @Test
  public void convertToDatabaseColumn_returnsNullIfInputIsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  public void convertToDatabaseColumn_convertsCorrectly() {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse("2019-09-01T01:01:01Z");
    assertThat(converter.convertToDatabaseColumn(zonedDateTime).toInstant())
        .isEqualTo(zonedDateTime.toInstant());
  }

  @Test
  public void convertToEntityAttribute_returnsNullIfInputIsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  public void convertToEntityAttribute_convertsCorrectly() {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse("2019-09-01T01:01:01Z");
    Instant instant = zonedDateTime.toInstant();
    assertThat(converter.convertToEntityAttribute(Timestamp.from(instant)))
        .isEqualTo(zonedDateTime);
  }

  @Test
  public void converter_generatesTimestampWithNormalizedZone() {
    ClaimsList claimsList =
        ClaimsList.create(
            ZonedDateTime.parse("2019-09-01T01:01:01Z"), ImmutableMap.of("label1", "key1"));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
    long revisionId = claimsList.getRevisionId();
    ClaimsList retrievedClaimsList =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ClaimsList.class, revisionId));
    assertThat(retrievedClaimsList.getCreationTimestamp().toString())
        .isEqualTo("2019-09-01T01:01:01Z");
  }

  @Test
  public void converter_convertsNonNormalizedZoneCorrectly() {
    ClaimsList claimsList =
        ClaimsList.create(
            ZonedDateTime.parse("2019-09-01T01:01:01Z[UTC]"), ImmutableMap.of("label1", "key1"));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
    long revisionId = claimsList.getRevisionId();
    ClaimsList retrievedClaimsList =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ClaimsList.class, revisionId));
    assertThat(retrievedClaimsList.getCreationTimestamp().toString())
        .isEqualTo("2019-09-01T01:01:01Z");
  }

  @Test
  public void converter_convertsNonUtcZoneCorrectly() {
    ClaimsList claimsList =
        ClaimsList.create(
            ZonedDateTime.parse("2019-09-01T01:01:01+05:00"), ImmutableMap.of("label1", "key1"));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
    long revisionId = claimsList.getRevisionId();
    ClaimsList retrievedClaimsList =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ClaimsList.class, revisionId));
    assertThat(retrievedClaimsList.getCreationTimestamp().toString())
        .isEqualTo("2019-08-31T20:01:01Z");
  }
}
