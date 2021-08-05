// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.Maps;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationStateTransition;
import java.util.Map;
import javax.persistence.Converter;
import org.joda.time.DateTime;

/** JPA converter for {@link DatabaseMigrationStateSchedule} transitions. */
@Converter(autoApply = true)
public class DatabaseMigrationScheduleTransitionConverter
    extends TimedTransitionPropertyConverterBase<MigrationState, MigrationStateTransition> {

  @Override
  Map.Entry<String, String> convertToDatabaseMapEntry(
      Map.Entry<DateTime, MigrationStateTransition> entry) {
    return Maps.immutableEntry(entry.getKey().toString(), entry.getValue().getValue().name());
  }

  @Override
  Map.Entry<DateTime, MigrationState> convertToEntityMapEntry(Map.Entry<String, String> entry) {
    return Maps.immutableEntry(
        DateTime.parse(entry.getKey()), MigrationState.valueOf(entry.getValue()));
  }

  @Override
  Class<MigrationStateTransition> getTimedTransitionSubclass() {
    return MigrationStateTransition.class;
  }
}
