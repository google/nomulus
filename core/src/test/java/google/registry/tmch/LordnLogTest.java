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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.tmch.LordnLog.Result;
import java.util.Map.Entry;
import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LordnLog}. */
@RunWith(JUnit4.class)
public class LordnLogTest {

  public static final ImmutableList<String> EXAMPLE_FROM_RFC =
      ImmutableList.of(
          "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,"
              + "0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=,"
              + "accepted,no-warnings,1",
          "roid,result-code",
          "SH8013-REP,2000");

  public static final ImmutableList<String> EXAMPLE_WITH_WARNINGS =
      ImmutableList.of(
          "1,2014-03-21T15:40:08.4Z,2014-03-21T15:35:28.0Z,"
              + "0000000000000004799,accepted,warnings-present,2",
          "roid,result-code",
          "19dc9b4-roid,3610",
          "1580e26-roid,3610");

  @Test
  public void testSuccess_parseFirstLine() {
    LordnLog log = LordnLog.parse(EXAMPLE_FROM_RFC);
    assertThat(log.getStatus()).isEqualTo(LordnLog.Status.ACCEPTED);
    assertThat(log.getLogCreation()).isEqualTo(DateTime.parse("2012-08-16T02:15:00.0Z"));
    assertThat(log.getLordnCreation()).isEqualTo(DateTime.parse("2012-08-16T00:00:00.0Z"));
    assertThat(log.getLogId())
        .isEqualTo("0000000000000478Nzs+3VMkR8ckuUynOLmyeqTmZQSbzDuf/R50n2n5QX4=");
    assertThat(log.hasWarnings()).isFalse();
  }

  @Test
  public void testSuccess_parseDnLines() {
    LordnLog log = LordnLog.parse(EXAMPLE_FROM_RFC);
    Result result = log.getResult("SH8013-REP");
    assertThat(result).isNotNull();
    assertThat(result.getCode()).isEqualTo(2000);
    assertThat(result.getDescription()).isEqualTo("OK");
    assertThat(result.getOutcome()).isEqualTo(Result.Outcome.OK);
  }

  @Test
  public void testSuccess_iterate() {
    for (Entry<String, Result> result : LordnLog.parse(EXAMPLE_FROM_RFC)) {
      assertThat(result.getKey()).isEqualTo("SH8013-REP");
      assertThat(result.getValue().getCode()).isEqualTo(2000);
      assertThat(result.getValue().getDescription()).isEqualTo("OK");
      assertThat(result.getValue().getOutcome()).isEqualTo(Result.Outcome.OK);
    }
  }

  @Test
  public void testSuccess_noDnLines() {
    LordnLog.parse(ImmutableList.of(
        "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,lolcat,accepted,no-warnings,0",
        "roid,result-code"));
  }

  @Test
  public void testFailure_noDnLineMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LordnLog.parse(
                ImmutableList.of(
                    "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,lolcat,accepted,no-warnings,1",
                    "roid,result-code")));
  }

  @Test
  public void testFailure_parseNull() {
    assertThrows(NullPointerException.class, () -> LordnLog.parse(null));
  }

  @Test
  public void testFailure_parseEmpty() {
    assertThrows(Exception.class, () -> LordnLog.parse(ImmutableList.of()));
  }

  @Test
  public void testFailure_parseMissingHeaderLine() {
    assertThrows(
        Exception.class,
        () ->
            LordnLog.parse(
                ImmutableList.of(
                    "1,2012-08-16T02:15:00.0Z,2012-08-16T00:00:00.0Z,lolcat,accepted,no-warnings,0")));
  }

  @Test
  public void testSuccess_toString() {
    assertThat(LordnLog.parse(EXAMPLE_WITH_WARNINGS).toString()).isEqualTo(
        "LordnLog{"
        + "logId=0000000000000004799, "
        + "status=ACCEPTED, "
        + "logCreation=2014-03-21T15:40:08.400Z, "
        + "lordnCreation=2014-03-21T15:35:28.000Z, "
        + "hasWarnings=true, "
        + "results={"
        + "19dc9b4-roid=Result{code=3610, outcome=WARNING, "
        + "description=DN reported outside of the time window}, "
        + "1580e26-roid=Result{code=3610, outcome=WARNING, "
        + "description=DN reported outside of the time window}"
        + "}}");
  }

  @Test
  public void testSuccess_resultToString() {
    assertThat(
        LordnLog.parse(EXAMPLE_FROM_RFC).iterator().next().toString())
            .isEqualTo("SH8013-REP=Result{code=2000, outcome=OK, description=OK}");
  }

  @Test
  public void testSuccess_withWarnings() {
    LordnLog log = LordnLog.parse(EXAMPLE_WITH_WARNINGS);
    assertThat(log.getStatus()).isEqualTo(LordnLog.Status.ACCEPTED);
    assertThat(log.getLogCreation()).isEqualTo(DateTime.parse("2014-03-21T15:40:08.4Z"));
    assertThat(log.getLordnCreation()).isEqualTo(DateTime.parse("2014-03-21T15:35:28.0Z"));
    assertThat(log.getLogId()).isEqualTo("0000000000000004799");
    assertThat(log.hasWarnings()).isTrue();

    assertThat(log.getResult("19dc9b4-roid").getCode()).isEqualTo(3610);
    assertThat(log.getResult("19dc9b4-roid").getOutcome()).isEqualTo(Result.Outcome.WARNING);
    assertThat(log.getResult("19dc9b4-roid").getDescription())
        .isEqualTo("DN reported outside of the time window");

    assertThat(log.getResult("1580e26-roid").getCode()).isEqualTo(3610);
    assertThat(log.getResult("1580e26-roid").getOutcome()).isEqualTo(Result.Outcome.WARNING);
    assertThat(log.getResult("1580e26-roid").getDescription())
        .isEqualTo("DN reported outside of the time window");
  }
}
