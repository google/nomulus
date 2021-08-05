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

package google.registry.tools;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.tools.params.TransitionListParameter.MigrationStateTransitions;
import org.joda.time.DateTime;

/** Command to set the Registry 3.0 database migration state schedule. */
@Parameters(
    separators = " =",
    commandDescription = "Set the current database migration state schedule.")
public class SetDatabaseMigrationStateCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final String WARNING_MESSAGE =
      "Attempting to change the schedule with an effect that would take place within the next 10 "
          + "minutes. The cache expiration duration is 5 minutes so this MAY BE DANGEROUS.\n";

  @Parameter(
      names = "--migration_schedule",
      converter = MigrationStateTransitions.class,
      validateWith = MigrationStateTransitions.class,
      required = true,
      description =
          "Comma-delimited list of database transitions, of the form"
              + " <time>=<migration-state>[,<time>=<migration-state>]*")
  ImmutableSortedMap<DateTime, MigrationState> transitionSchedule;

  @Override
  protected String prompt() {
    return jpaTm()
        .transact(
            () -> {
              StringBuilder result = new StringBuilder();
              DateTime now = jpaTm().getTransactionTime();
              DateTime nextTransition = transitionSchedule.ceilingKey(now);
              if (nextTransition != null && nextTransition.isBefore(now.plusMinutes(10))) {
                result.append(WARNING_MESSAGE);
              }
              return result
                  .append(String.format("Set new migration state schedule %s?", transitionSchedule))
                  .toString();
            });
  }

  @Override
  protected String execute() {
    jpaTm().transact(() -> DatabaseMigrationStateSchedule.set(transitionSchedule));
    return String.format("Successfully set new migration state schedule %s", transitionSchedule);
  }
}
