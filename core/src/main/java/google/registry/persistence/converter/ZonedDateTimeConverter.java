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

package google.registry.persistence.converter;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * JPA converter to for storing/retrieving {@link ZonedDateTime} objects.
 *
 * <p>Hibernate provides a default converter for {@link ZonedDateTime}, but it converts timestamp to
 * a non-normalized format, e.g., 2019-09-01T01:01:01Z will be converted to
 * 2019-09-01T01:01:01Z[UTC]. This converter solves that problem by explicitly calling {@link
 * ZoneId#normalized()} to normalize the zone id.
 */
@Converter(autoApply = true)
public class ZonedDateTimeConverter implements AttributeConverter<ZonedDateTime, Timestamp> {

  @Override
  @Nullable
  public Timestamp convertToDatabaseColumn(@Nullable ZonedDateTime attribute) {
    return attribute == null ? null : Timestamp.from(attribute.toInstant());
  }

  @Override
  @Nullable
  public ZonedDateTime convertToEntityAttribute(@Nullable Timestamp dbData) {
    return dbData == null
        ? null
        : ZonedDateTime.ofInstant(dbData.toInstant(), ZoneId.of("UTC").normalized());
  }
}
