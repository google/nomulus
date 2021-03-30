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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import google.registry.model.tmch.ClaimsListShard;
import java.util.List;
import org.joda.time.DateTime;

/**
 * Claims List (MarksDB DNL CSV) Parser.
 *
 * <p>This is a quick and dirty CSV parser made specifically for the DNL CSV format defined in the
 * TMCH specification. It doesn't support any fancy CSV features like quotes.
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">
 *     TMCH functional specifications - DNL List file</a>
 */
public class ClaimsListParser {

  /**
   * Converts the lines from the DNL CSV file into a {@link ClaimsListShard} object.
   *
   * <p>Please note that this does <b>not</b> insert the object into Datastore.
   */
  public static ClaimsListShard parse(List<String> lines) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();

    // First line: <version>,<DNL List creation datetime>
    List<String> firstLine = Splitter.on(',').splitToList(lines.get(0));
    checkArgument(firstLine.size() == 2, String.format(
        "Line 1: Expected 2 elements, found %d", firstLine.size()));

    int version = Integer.parseInt(firstLine.get(0));
    DateTime creationTime = DateTime.parse(firstLine.get(1));
    checkArgument(version == 1, String.format(
        "Line 1: Expected version 1, found %d", version));

    // Second line contains headers: DNL,lookup-key,insertion-datetime
    List<String> secondLine = Splitter.on(',').splitToList(lines.get(1));
    checkArgument(secondLine.size() == 3, String.format(
        "Line 2: Expected 3 elements, found %d", secondLine.size()));
    checkArgument("DNL".equals(secondLine.get(0)), String.format(
        "Line 2: Expected header \"DNL\", found \"%s\"", secondLine.get(0)));
    checkArgument("lookup-key".equals(secondLine.get(1)), String.format(
        "Line 2: Expected header \"lookup-key\", found \"%s\"", secondLine.get(1)));
    checkArgument("insertion-datetime".equals(secondLine.get(2)), String.format(
        "Line 2: Expected header \"insertion-datetime\", found \"%s\"", secondLine.get(2)));

    // Subsequent lines: <DNL>,<lookup key>,<DNL insertion datetime>
    for (int i = 2; i < lines.size(); i++) {
      List<String> currentLine = Splitter.on(',').splitToList(lines.get(i));
      checkArgument(currentLine.size() == 3, String.format(
          "Line %d: Expected 3 elements, found %d", i + 1, currentLine.size()));

      String label = currentLine.get(0);
      String lookupKey = currentLine.get(1);
      DateTime.parse(currentLine.get(2));  // This is the insertion time, currently unused.
      builder.put(label, lookupKey);
    }

    return ClaimsListShard.create(creationTime, builder.build());
  }
}
