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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.DatastoreAdmin.ListOperations;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.Clock;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Command that lists Datastore operations. */
@DeleteAfterMigration
@Parameters(separators = " =", commandDescription = "List Datastore operations.")
public class ListDatastoreOperationsCommand implements Command {

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  @Nullable
  @Parameter(
      names = "--start_time_filter",
      description =
          "Duration relative to current time, used to filter operations by start time. "
              + "Value is in ISO-8601 format, e.g., PT10S for 10 seconds.")
  private Duration startTimeFilter;

  @Inject DatastoreAdmin datastoreAdmin;
  @Inject Clock clock;

  @Override
  public void run() throws Exception {
    ListOperations listOperations =
        getQueryFilter().map(datastoreAdmin::list).orElseGet(() -> datastoreAdmin.listAll());
    System.out.println(JSON_FACTORY.toPrettyString(listOperations.execute()));
  }

  private Optional<String> getQueryFilter() {
    if (startTimeFilter == null) {
      return Optional.empty();
    }

    DateTime earliestStartingTime = clock.nowUtc().minus(startTimeFilter);
    return Optional.of(
        String.format("metadata.common.startTime>\"%s\"", earliestStartingTime));
  }
}
