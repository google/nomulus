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

import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import javax.persistence.Converter;

/** JPA converter for {@link DatabaseMigrationStateSchedule} transitions. */
@DeleteAfterMigration
@Converter(autoApply = true)
public class DatabaseMigrationScheduleTransitionConverter
    extends TimedTransitionPropertyConverterBase<MigrationState> {

  @Override
  protected String convertValueToString(MigrationState value) {
    return value.name();
  }

  @Override
  protected MigrationState convertStringToValue(String string) {
    return MigrationState.valueOf(string);
  }
}
