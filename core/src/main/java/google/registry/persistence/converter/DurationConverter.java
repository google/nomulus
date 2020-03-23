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

import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.joda.time.Duration;

/** JPA converter to for storing/retrieving {@link org.joda.time.DateTime} objects. */
@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, Long> {

  @Override
  @Nullable
  public Long convertToDatabaseColumn(@Nullable Duration duration) {
    return duration == null ? null : duration.getMillis();
  }

  @Override
  @Nullable
  public Duration convertToEntityAttribute(@Nullable Long dbData) {
    return dbData == null ? null : new Duration(dbData);
  }
}
