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

import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.tools.params.TransitionListParameter.PrimaryDatabaseTransitions;
import org.joda.time.DateTime;

/** Command to update {@link DatabaseTransitionSchedule}. */
@Parameters(
    separators = " =",
    commandDescription = "Set the database transition schedule for transition id.")
public class SetDatabaseTransitionScheduleCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(
      names = "--transition_schedule",
      converter = PrimaryDatabaseTransitions.class,
      validateWith = PrimaryDatabaseTransitions.class,
      description =
          "Comma-delimited list of database transitions, of the form"
              + " <time>=<primary-database>[,<time>=<primary-database>]*")
  ImmutableSortedMap<DateTime, PrimaryDatabase> transitionSchedule;

  @Parameter(
      names = "--transition_id",
      required = true,
      description = "Transition id string for the schedule being updated")
  private TransitionId transitionId;

  @Override
  protected String prompt() {
    return String.format(
        "Insert new schedule %s for transition ID %s?", transitionSchedule, transitionId);
  }

  @Override
  protected String execute() {
    DatabaseTransitionSchedule newSchedule =
        DatabaseTransitionSchedule.create(
            transitionId,
            TimedTransitionProperty.fromValueMap(
                transitionSchedule, PrimaryDatabaseTransition.class));
    ofyTm().transact(() -> ofyTm().put(newSchedule));
    return String.format(
        "Inserted new schedule %s for transition ID %s.", transitionSchedule, transitionId);
  }
}
