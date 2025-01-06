// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tmch;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import google.registry.model.tmch.ClaimsList;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;

/**
 * Claims List (MarksDB DNL CSV) Parser.
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">
 *     TMCH functional specifications - DNL List file</a>
 */
public class ClaimsListParser {

  /**
   * Converts the lines from the DNL CSV file into a {@link ClaimsList} object.
   *
   * <p>Please note that this does <b>not</b> insert the object into the DB.
   */
  public static ClaimsList parse(List<String> lines) throws IOException {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();

    // First line: <version>,<DNL List creation datetime>
    List<String> firstLine = Splitter.on(',').splitToList(lines.get(0));
    checkArgument(firstLine.size() == 2, String.format(
        "Line 1: Expected 2 elements, found %d", firstLine.size()));

    int version = Integer.parseInt(firstLine.get(0));
    DateTime creationTime = DateTime.parse(firstLine.get(1));
    checkArgument(version == 1, String.format(
        "Line 1: Expected version 1, found %d", version));

    // Note: we have to skip the first line because it contains the version metadata
    CSVParser csv =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            .parse(new StringReader(Joiner.on('\n').join(lines.subList(1, lines.size()))));
    for (CSVRecord record : csv) {
      String label = record.get("DNL");
      String lookupKey = record.get("lookup-key");
      builder.put(label, lookupKey);
    }

    return ClaimsList.create(creationTime, builder.build());
  }
}
