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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Iterables;
import google.registry.model.replay.SqlReplayCheckpoint;
import java.util.List;
import org.joda.time.DateTime;

/** Command to set {@link SqlReplayCheckpoint} to a particular, post-initial-population time. */
@Parameters(separators = " =", commandDescription = "Set SqlReplayCheckpoint to a particular time")
public class SetSqlReplayCheckpointCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(description = "Time to which SqlReplayCheckpoint will be set", required = true)
  List<DateTime> mainParameters;

  @Override
  protected String prompt() {
    checkArgument(mainParameters.size() == 1, "Must provide exactly one DateTime to set");
    return String.format(
        "Set SqlReplayCheckpoint to %s?", Iterables.getOnlyElement(mainParameters));
  }

  @Override
  protected String execute() {
    DateTime dateTime = Iterables.getOnlyElement(mainParameters);
    jpaTm().transact(() -> SqlReplayCheckpoint.set(dateTime));
    return String.format("Set SqlReplayCheckpoint time to %s", dateTime);
  }
}
