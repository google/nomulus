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

import javax.persistence.Converter;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;

/** JPA converter for {@link LocalDate}. */
@Converter(autoApply = true)
public class LocalDateConverter extends ToStringConverterBase<LocalDate> {

  /** Converts the string (a date in ISO-8601 format) into a LocalDate. */
  @Override
  public LocalDate convertToEntityAttribute(String columnValue) {
    return (columnValue == null) ? null : LocalDate.parse(columnValue, ISODateTimeFormat.date());
  }
}
