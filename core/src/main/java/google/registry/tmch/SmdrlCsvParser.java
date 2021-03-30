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

package google.registry.tmch;

import static com.google.common.base.Preconditions.checkArgument;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import google.registry.model.smd.SignedMarkRevocationList;
import java.util.List;
import org.joda.time.DateTime;

/**
 * Signed Mark Data Revocation List (SMDRL) CSV Parser
 *
 * <p>This is a quick and dirty CSV parser made specifically for the SMDRL CSV format defined in
 * the TMCH specification. It doesn't support any fancy CSV features like quotes.
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.2">
 *     TMCH functional specifications - SMD Revocation List</a>
 */
public final class SmdrlCsvParser {

  /** Converts the lines from the DNL CSV file into a data structure. */
  public static SignedMarkRevocationList parse(List<String> lines) {
    ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();

    // First line: <version>,<SMD Revocation List creation datetime>
    List<String> firstLine = Splitter.on(',').splitToList(lines.get(0));
    checkArgument(firstLine.size() == 2, String.format(
        "Line 1: Expected 2 elements, found %d", firstLine.size()));
    int version = Integer.parseInt(firstLine.get(0));
    checkArgument(version == 1, String.format(
        "Line 1: Expected version 1, found %d", version));
    DateTime creationTime = DateTime.parse(firstLine.get(1)).withZone(UTC);

    // Second line contains headers: smd-id,insertion-datetime
    List<String> secondLine = Splitter.on(',').splitToList(lines.get(1));
    checkArgument(secondLine.size() == 2, String.format(
        "Line 2: Expected 2 elements, found %d", secondLine.size()));
    checkArgument("smd-id".equals(secondLine.get(0)), String.format(
        "Line 2: Expected header \"smd-id\", found \"%s\"", secondLine.get(0)));
    checkArgument("insertion-datetime".equals(secondLine.get(1)), String.format(
        "Line 2: Expected header \"insertion-datetime\", found \"%s\"", secondLine.get(1)));

    // Subsequent lines: <smd-id>,<revoked SMD datetime>
    for (int i = 2; i < lines.size(); i++) {
      List<String> currentLine = Splitter.on(',').splitToList(lines.get(i));
      checkArgument(currentLine.size() == 2, String.format(
          "Line %d: Expected 2 elements, found %d", i + 1, currentLine.size()));
      String smdId = currentLine.get(0);
      DateTime revokedTime = DateTime.parse(currentLine.get(1));
      revokes.put(smdId, revokedTime);
    }

    return SignedMarkRevocationList.create(creationTime, revokes.build());
  }
}
