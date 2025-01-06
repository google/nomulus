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

package google.registry.persistence.converter;

import static org.joda.time.DateTimeZone.UTC;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** JPA converter to for storing/retrieving {@link org.joda.time.DateTime} objects. */
@Converter(autoApply = true)
public class DateTimeConverter implements AttributeConverter<DateTime, ZonedDateTime> {

  @Override
  @Nullable
  public ZonedDateTime convertToDatabaseColumn(@Nullable DateTime attribute) {
    return attribute == null
        ? null
        : ZonedDateTime.ofInstant(Instant.ofEpochMilli(attribute.getMillis()), ZoneOffset.UTC);
  }

  @Override
  @Nullable
  public DateTime convertToEntityAttribute(@Nullable ZonedDateTime dbData) {
    return (dbData == null) ? null : new DateTime(dbData.toInstant().toEpochMilli(), UTC);
  }
}
