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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.UpdateAutoTimestamp;
import google.registry.util.DateTimeUtils;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA converter for storing/retrieving UpdateAutoTimestamp objects. */
@Converter(autoApply = true)
public class UpdateAutoTimestampConverter
    implements AttributeConverter<UpdateAutoTimestamp, Timestamp> {

  @Override
  public Timestamp convertToDatabaseColumn(UpdateAutoTimestamp entity) {
    return Timestamp.from(DateTimeUtils.toZonedDateTime(jpaTm().getTransactionTime()).toInstant());
  }

  @Override
  @Nullable
  public UpdateAutoTimestamp convertToEntityAttribute(@Nullable Timestamp columnValue) {
    if (columnValue == null) {
      return null;
    }
    ZonedDateTime zdt = ZonedDateTime.ofInstant(columnValue.toInstant(), ZoneOffset.UTC);
    return UpdateAutoTimestamp.create(DateTimeUtils.toJodaDateTime(zdt));
  }
}
