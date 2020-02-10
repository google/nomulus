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

import static com.google.common.base.MoreObjects.firstNonNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.CreateAutoTimestamp;
import google.registry.util.DateTimeUtils;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.joda.time.DateTime;

/** JPA converter to for storing/retrieving CreateAutoTimestamp objects. */
@Converter(autoApply = true)
public class CreateAutoTimestampConverter
    implements AttributeConverter<CreateAutoTimestamp, Timestamp> {

  @Override
  public Timestamp convertToDatabaseColumn(CreateAutoTimestamp entity) {
    DateTime dateTime = firstNonNull(entity.getTimestamp(), jpaTm().getTransactionTime());
    return Timestamp.from(DateTimeUtils.toZonedDateTime(dateTime).toInstant());
  }

  @Override
  @Nullable
  public CreateAutoTimestamp convertToEntityAttribute(@Nullable Timestamp columnValue) {
    if (columnValue == null) {
      return null;
    }
    ZonedDateTime zdt = ZonedDateTime.ofInstant(columnValue.toInstant(), ZoneOffset.UTC);
    return CreateAutoTimestamp.create(DateTimeUtils.toJodaDateTime(zdt));
  }
}
