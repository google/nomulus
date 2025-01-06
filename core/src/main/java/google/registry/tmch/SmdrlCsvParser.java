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
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import google.registry.model.smd.SignedMarkRevocationList;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;

/**
 * Signed Mark Data Revocation List (SMDRL) CSV Parser
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.2">
 *     TMCH functional specifications - SMD Revocation List</a>
 */
public final class SmdrlCsvParser {

  /** Converts the lines from the DNL CSV file into a data structure. */
  public static SignedMarkRevocationList parse(List<String> lines) throws IOException {
    ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();

    // First line: <version>,<SMD Revocation List creation datetime>
    List<String> firstLine = Splitter.on(',').splitToList(lines.get(0));
    checkArgument(
        firstLine.size() == 2,
        String.format("Line 1: Expected 2 elements, found %d", firstLine.size()));
    int version = Integer.parseInt(firstLine.get(0));
    checkArgument(version == 1, String.format("Line 1: Expected version 1, found %d", version));
    DateTime creationTime = DateTime.parse(firstLine.get(1)).withZone(UTC);

    // Note: we have to skip the first line because it contains the version metadata
    CSVParser csv =
        CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            .parse(new StringReader(Joiner.on('\n').join(lines.subList(1, lines.size()))));

    for (CSVRecord record : csv) {
      String smdId = record.get("smd-id");
      DateTime revokedTime = DateTime.parse(record.get("insertion-datetime"));
      revokes.put(smdId, revokedTime);
    }
    return SignedMarkRevocationList.create(creationTime, revokes.build());
  }
}
