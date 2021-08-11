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

import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.googlecode.objectify.Key;
import google.registry.model.tld.label.ReservedList;
import google.registry.persistence.VKey;
import java.nio.file.Files;
import java.util.List;

/** Command to safely update {@link ReservedList}. */
@Parameters(separators = " =", commandDescription = "Update a ReservedList.")
final class UpdateReservedListCommand extends CreateOrUpdateReservedListCommand {

  @Override
  protected void init() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(input) : name;
    ReservedList existingReservedList =
        ReservedList.get(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update reserved list %s because it doesn't exist.", name)));
    boolean shouldPublish =
        this.shouldPublish == null ? existingReservedList.getShouldPublish() : this.shouldPublish;
    List<String> allLines = Files.readAllLines(input, UTF_8);
    ReservedList.Builder updated =
        existingReservedList
            .asBuilder()
            .setReservedListMapFromLines(allLines)
            .setShouldPublish(shouldPublish);
    reservedList = updated.build();
    // only call stageEntityChange if there are changes in entries

    if (!existingReservedList
        .getReservedListEntries()
        .equals(reservedList.getReservedListEntries())) {
      // calls the stageEntityChange method that takes old entity, new entity and a new vkey;
      // a vkey has to be created here explicitly for ReservedList instances.
      // ReservedList is a sqlEntity; it triggers the static method Vkey.create(Key<?> ofyCall),
      // which invokes a static ReservedList.createVkey(Key ofyKey) method that does not exist.
      // the sql primary key field (revisionId) is only set when it's being persisted;
      stageEntityChange(
          existingReservedList,
          reservedList,
          VKey.createOfy(ReservedList.class, Key.create(existingReservedList)));
    }
  }
}
