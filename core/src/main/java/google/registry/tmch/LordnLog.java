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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.re2j.Pattern;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.joda.time.DateTime;

/**
 * Parser of LORDN log responses from the MarksDB server during the NORDN process.
 *
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3.1">
 *     TMCH functional specifications - LORDN Log File</a>
 */
@Immutable
public final class LordnLog implements Iterable<Entry<String, LordnLog.Result>> {

  /** Indicates whether or not the LORDN upload succeeded. */
  public enum Status { ACCEPTED, REJECTED }

  /** Result code for individual DN lines. */
  @Immutable
  public static final class Result {

    /** Outcome categories for individual DN lines. */
    public enum Outcome { OK, WARNING, ERROR }

    private final int code;
    private final String description;
    private final Outcome outcome;

    private Result(int code, String description) {
      this.code = code;
      this.description = description;
      if (2000 <= code && code <= 2099) {
        this.outcome = Outcome.OK;
      } else if (3500 <= code && code <= 3699) {
        this.outcome = Outcome.WARNING;
      } else if (4500 <= code && code <= 4699) {
        this.outcome = Outcome.ERROR;
      } else {
        throw new IllegalArgumentException("Invalid DN result code: " + code);
      }
    }

    public int getCode() {
      return code;
    }

    public String getDescription() {
      return description;
    }

    public Outcome getOutcome() {
      return outcome;
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("code", code)
          .add("outcome", outcome)
          .add("description", description)
          .toString();
    }
  }

  private static final ImmutableMap<Integer, Result> RESULTS =
      new ImmutableMap.Builder<Integer, Result>()
          .put(2000, new Result(2000, "OK"))
          .put(2001, new Result(2001, "OK but not processed"))
          .put(3601, new Result(3601, "TCN Acceptance Date after Registration Date"))
          .put(3602, new Result(3602, "Duplicate DN Line"))
          .put(3603, new Result(3603, "DNROID Notified Earlier"))
          .put(3604, new Result(3604, "TCN Checksum invalid"))
          .put(3605, new Result(3605, "TCN Expired"))
          .put(3606, new Result(3606, "Wrong TCNID used"))
          .put(3609, new Result(3609, "Invalid SMD used"))
          .put(3610, new Result(3610, "DN reported outside of the time window"))
          .put(3611, new Result(3611, "DN does not match the labels in SMD"))
          .put(3612, new Result(3612, "SMDID does not exist"))
          .put(3613, new Result(3613, "SMD was revoked when used"))
          .put(3614, new Result(3614, "TCNID does not exist"))
          .put(3615, new Result(3615, "Recent-dnl-insertion outside of the time window"))
          .put(
              3616, new Result(3616, "Registration Date of DN in claims before the end of Sunrise"))
          .put(3617, new Result(3617, "Registrar has not been approved by the TMDB"))
          .put(3618, new Result(3618, "Registration Date of DN outside of QLP period"))
          .put(3619, new Result(3619, "TCN was not valid at the time of acknowledgement"))
          .put(4501, new Result(4501, "Syntax Error in DN Line"))
          .put(4601, new Result(4601, "Invalid TLD used"))
          .put(4602, new Result(4602, "Registrar ID Invalid"))
          .put(4603, new Result(4603, "Registration Date in the future"))
          .put(4606, new Result(4606, "TLD not in Sunrise or Claims"))
          .put(4607, new Result(4607, "Application Date in the future"))
          .put(4608, new Result(4608, "Application Date is later than Registration Date"))
          .put(4609, new Result(4609, "TCNID wrong syntax"))
          .put(4610, new Result(4610, "TCN Acceptance Date is in the future"))
          .put(4611, new Result(4611, "Label has never existed in the TMDB"))
          .build();

  /** Base64 matcher between one and sixty characters. */
  private static final Pattern LOG_ID_PATTERN = Pattern.compile("[-A-Za-z0-9+/=]{1,60}");

  private final String logId;
  private final Status status;
  private final DateTime logCreation;
  private final DateTime lordnCreation;
  private final boolean hasWarnings;
  private final ImmutableMap<String, Result> results;

  private LordnLog(
      String logId,
      Status status,
      DateTime logCreation,
      DateTime lordnCreation,
      boolean hasWarnings,
      ImmutableMap<String, Result> results) {
    this.logId = logId;
    this.status = status;
    this.logCreation = logCreation;
    this.lordnCreation = lordnCreation;
    this.hasWarnings = hasWarnings;
    this.results = results;
  }

  public String getLogId() {
    return logId;
  }

  public Status getStatus() {
    return status;
  }

  public DateTime getLogCreation() {
    return logCreation;
  }

  public DateTime getLordnCreation() {
    return lordnCreation;
  }

  public boolean hasWarnings() {
    return hasWarnings;
  }

  @Nullable
  public Result getResult(String roid) {
    return results.get(roid);
  }

  @Override
  public Iterator<Entry<String, Result>> iterator() {
    return results.entrySet().iterator();
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("logId", logId)
        .add("status", status)
        .add("logCreation", logCreation)
        .add("lordnCreation", lordnCreation)
        .add("hasWarnings", hasWarnings)
        .add("results", results)
        .toString();
  }

  /** Turns lines of NORDN log returned by MarksDB into a data structure. */
  public static LordnLog parse(List<String> lines) {
    // First line: <version>,<LORDN Log creation datetime>,<LORDN file creation datetime>,
    //             <LORDN Log Identifier>,<Status flag>,<Warning flag>,<Number of DN Lines>
    List<String> firstLine = Splitter.on(',').splitToList(lines.get(0));
    checkArgument(firstLine.size() == 7, String.format(
        "Line 1: Expected 7 elements, found %d", firstLine.size()));

    // +  <version>, version of the file, this field MUST be 1.
    int version = Integer.parseInt(firstLine.get(0));
    checkArgument(version == 1, String.format(
        "Line 1: Expected version 1, found %d", version));

    // +  <LORDN Log creation datetime>, date and time in UTC that the
    //    LORDN Log was created.
    DateTime logCreation = DateTime.parse(firstLine.get(1));

    // +  <LORDN file creation datetime>, date and time in UTC of
    //    creation for the LORDN file that this log file is referring
    //    to.
    DateTime lordnCreation = DateTime.parse(firstLine.get(2));

    // +  <LORDN Log Identifier>, unique identifier of the LORDN Log
    //    provided by the TMDB.  This identifier could be used by the
    //    Registry Operator to unequivocally identify the LORDN Log.
    //    The identified will be a string of a maximum LENGTH of 60
    //    characters from the Base 64 alphabet.
    String logId = firstLine.get(3);
    checkArgument(LOG_ID_PATTERN.matcher(logId).matches(),
        "Line 1: Log ID does not match base64 pattern: %s", logId);

    // +  <Status flag>, whether the LORDN file has been accepted for
    //    processing by the TMDB.  Possible values are "accepted" or
    //    "rejected".
    Status status = Status.valueOf(Ascii.toUpperCase(firstLine.get(4)));

    // +  <Warning flag>, whether the LORDN Log has any warning result
    //    codes.  Possible values are "no-warnings" or "warnings-
    //    present".
    boolean hasWarnings = !"no-warnings".equals(firstLine.get(5));

    // +  <Number of DN Lines>, number of DNs effective allocations
    //    processed in the LORDN file.
    int dnLines = Integer.parseInt(firstLine.get(6));
    int actual = lines.size() - 2;
    checkArgument(
        dnLines == actual,
        "Line 1: Number of entries (%s) differs from declaration (%s)",
        String.valueOf(actual),
        String.valueOf(dnLines));

    // Second line contains headers: roid,result-code
    checkArgument(lines.get(1).equals("roid,result-code"),
        "Line 2: Unexpected header list: %s", lines.get(1));

    // Subsequent lines: <roid>,<result code>
    ImmutableMap.Builder<String, Result> builder = new ImmutableMap.Builder<>();
    for (int i = 2; i < lines.size(); i++) {
      List<String> currentLine = Splitter.on(',').splitToList(lines.get(i));
      checkArgument(currentLine.size() == 2, String.format(
          "Line %d: Expected 2 elements, found %d", i + 1, currentLine.size()));
      String roid = currentLine.get(0);
      int code = Integer.parseInt(currentLine.get(1));
      Result result = checkNotNull(RESULTS.get(code), "Line %s: Unknown result code: %s", i, code);
      builder.put(roid, result);
    }

    return new LordnLog(logId, status, logCreation, lordnCreation, hasWarnings, builder.build());
  }
}
