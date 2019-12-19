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
import static google.registry.model.registry.Registries.assertTldExists;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import google.registry.model.registry.label.ReservedList;
import google.registry.schema.tld.ReservedListDao;
import java.nio.file.Files;
import java.util.List;
import org.joda.time.DateTime;

/** Command to create a {@link ReservedList} on Datastore. */
@Parameters(separators = " =", commandDescription = "Create a ReservedList in Datastore.")
final class CreateReservedListCommand extends CreateOrUpdateReservedListCommand {

  @VisibleForTesting
  static final String INVALID_FORMAT_ERROR_MESSAGE =
      "The name must be in the format {tld|common}_list-name "
      + "and contain only letters, numbers, and hyphens, plus a single underscore delimiter";

  @Parameter(
      names = {"-o", "--override"},
      description = "Override restrictions on reserved list naming")
  boolean override;

  @Override
  protected void init() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(input) : name;
    checkArgument(
        !ReservedList.get(name).isPresent(),
        "A reserved list already exists by this name");
    if (!override) {
      validateListName(name);
    }
    DateTime now = DateTime.now(UTC);
    List<String> allLines = Files.readAllLines(input, UTF_8);
    boolean shouldPublish = this.shouldPublish == null || this.shouldPublish;
    ReservedList reservedList =
        new ReservedList.Builder()
            .setName(name)
            .setReservedListMapFromLines(allLines)
            .setShouldPublish(shouldPublish)
            .setCreationTime(now)
            .setLastUpdateTime(now)
            .build();
    stageEntityChange(null, reservedList);
    if (alsoCloudSql) {
      cloudSqlReservedList =
          google.registry.schema.tld.ReservedList.create(
              name, shouldPublish, parseToReservationsByLabels(allLines));
    }
  }

  @Override
  void saveToCloudSql() {
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  !ReservedListDao.checkExists(cloudSqlReservedList.getName()),
                  "A reserved list of this name already exists: %s.",
                  cloudSqlReservedList.getName());
              ReservedListDao.save(cloudSqlReservedList);
            });
  }

  private static void validateListName(String name) {
    List<String> nameParts = Splitter.on('_').splitToList(name);
    checkArgument(nameParts.size() == 2, INVALID_FORMAT_ERROR_MESSAGE);
    String tld = nameParts.get(0);
    if (!tld.equals("common")) {
      assertTldExists(tld);
    }
    checkArgument(nameParts.get(1).matches("[-a-zA-Z0-9]+"), INVALID_FORMAT_ERROR_MESSAGE);
  }
}
