// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.registry.label.ReservedList;
import google.registry.util.SystemClock;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;

/** Command to safely update {@link ReservedList} on Datastore. */
@Parameters(separators = " =", commandDescription = "Update a ReservedList in Datastore.")
final class UpdateReservedListCommand extends CreateOrUpdateReservedListCommand {

  @Override
  protected void init() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(input) : name;
    Optional<ReservedList> existing = ReservedList.get(name);
    checkArgument(
        existing.isPresent(), "Could not update reserved list %s because it doesn't exist.", name);
    boolean shouldPublish =
        this.shouldPublish == null ? existing.get().getShouldPublish() : this.shouldPublish;
    List<String> allLines = Files.readAllLines(input, UTF_8);
    DateTime now = new SystemClock().nowUtc();
    ReservedList.Builder updated =
        existing
            .get()
            .asBuilder()
            .setReservedListMapFromLines(allLines)
            .setLastUpdateTime(now)
            .setShouldPublish(shouldPublish);
    reservedList = updated.build();
  }
}
