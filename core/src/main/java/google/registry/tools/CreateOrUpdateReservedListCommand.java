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
import static google.registry.model.registry.label.BaseDomainLabelList.splitOnComment;

import com.beust.jcommander.Parameter;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registry.label.ReservationType;
import google.registry.schema.tld.ReservedList.ReservedEntry;
import google.registry.tools.params.PathParameter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Base class for specification of command line parameters common to creating and updating reserved
 * lists.
 */
public abstract class CreateOrUpdateReservedListCommand extends MutatingCommand {

  static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Nullable
  @Parameter(
      names = {"-n", "--name"},
      description = "The name of this reserved list (defaults to filename if not specified).")
  String name;

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of new reserved list.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path input;

  @Nullable
  @Parameter(
      names = "--should_publish",
      description =
          "Whether the list is published to the concatenated list on Drive (defaults to true).",
      arity = 1)
  Boolean shouldPublish;

  @Parameter(
      names = {"--also_cloud_sql"},
      description =
          "Persist reserved list to Cloud SQL in addition to Datastore; defaults to false.")
  boolean alsoCloudSql;

  google.registry.schema.tld.ReservedList cloudSqlReservedList;

  abstract void saveToCloudSql();

  @Override
  protected String execute() throws Exception {
    // Save the list to Datastore and output its response.
    String output = super.execute();
    logger.atInfo().log(output);

    String cloudSqlMessage;
    if (alsoCloudSql) {
      cloudSqlMessage =
          String.format(
              "Saved reserved list %s with %d entries",
              name, cloudSqlReservedList.getLabelsToReservations().size());
      try {
        logger.atInfo().log("Saving reserved list to Cloud SQL for TLD %s", name);
        saveToCloudSql();
        logger.atInfo().log(cloudSqlMessage);
      } catch (Throwable e) {
        cloudSqlMessage =
            "Unexpected error saving reserved list to Cloud SQL from nomulus tool command";
        logger.atSevere().withCause(e).log(cloudSqlMessage);
      }
    } else {
      cloudSqlMessage = "Persisting reserved list to Cloud SQL is not enabled";
    }
    return cloudSqlMessage;
  }

  /** Turns the list CSV data into a map of labels to {@link ReservedEntry}. */
  static ImmutableMap<String, ReservedEntry> parseToReservationsByLabels(Iterable<String> lines) {
    Map<String, ReservedEntry> labelsToEntries = Maps.newHashMap();
    Multiset<String> duplicateLabels = HashMultiset.create();
    for (String originalLine : lines) {
      List<String> lineAndComment = splitOnComment(originalLine);
      if (lineAndComment.isEmpty()) {
        continue;
      }
      String line = lineAndComment.get(0);
      String comment = lineAndComment.get(1);
      List<String> parts = Splitter.on(',').trimResults().splitToList(line);
      checkArgument(
          parts.size() == 2 || parts.size() == 3,
          "Could not parse line in reserved list: %s",
          originalLine);
      String label = parts.get(0);
      ReservationType reservationType = ReservationType.valueOf(parts.get(1));
      ReservedEntry reservedEntry = ReservedEntry.create(reservationType, comment);
      // Check if the label was already processed for this list (which is an error), and if so,
      // accumulate it so that a list of all duplicates can be thrown.
      if (labelsToEntries.containsKey(label)) {
        duplicateLabels.add(label, duplicateLabels.contains(label) ? 1 : 2);
      } else {
        labelsToEntries.put(label, reservedEntry);
      }
    }
    if (!duplicateLabels.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Reserved list cannot contain duplicate labels. Dupes (with counts) were: %s",
              duplicateLabels));
    }
    return ImmutableMap.copyOf(labelsToEntries);
  }
}
