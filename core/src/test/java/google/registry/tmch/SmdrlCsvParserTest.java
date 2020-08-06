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
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.joda.time.Duration.millis;
import static org.joda.time.Duration.standardDays;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link SmdrlCsvParser}. */
class SmdrlCsvParserTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().build();

  private final FakeClock clock = new FakeClock();

  private static final CharSource SMDRL_LATEST_CSV =
      TmchTestData.loadBytes("smdrl-latest.csv").asCharSource(US_ASCII);

  @Test
  void testParse() throws Exception {
    SignedMarkRevocationList smdrl = SmdrlCsvParser.parse(SMDRL_LATEST_CSV.readLines());
    assertThat(smdrl.size()).isEqualTo(150);
    assertThat(smdrl.getCreationTime()).isEqualTo(DateTime.parse("2013-11-24T23:30:04.3Z"));
  }

  @Test
  void testFirstRow() throws Exception {
    SignedMarkRevocationList smdrl = SmdrlCsvParser.parse(SMDRL_LATEST_CSV.readLines());
    clock.setTo(DateTime.parse("2013-08-09T12:00:00.0Z"));
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", clock.nowUtc())).isTrue();
    clock.setTo(clock.nowUtc().minusMillis(1));
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", clock.nowUtc())).isFalse();
    clock.advanceBy(millis(2));
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", clock.nowUtc())).isTrue();
  }

  @Test
  void testLastRow() throws Exception {
    SignedMarkRevocationList smdrl = SmdrlCsvParser.parse(SMDRL_LATEST_CSV.readLines());
    clock.setTo(DateTime.parse("2013-08-09T12:00:00.0Z"));
    assertThat(smdrl.isSmdRevoked("0000002211373633641407-65535", clock.nowUtc())).isTrue();
    clock.setTo(clock.nowUtc().minusMillis(1));
    assertThat(smdrl.isSmdRevoked("0000002211373633641407-65535", clock.nowUtc())).isFalse();
    clock.advanceBy(millis(2));
    assertThat(smdrl.isSmdRevoked("0000002211373633641407-65535", clock.nowUtc())).isTrue();
  }

  @Test
  void testRowWithDifferentDate() throws Exception {
    SignedMarkRevocationList smdrl = SmdrlCsvParser.parse(SMDRL_LATEST_CSV.readLines());
    clock.setTo(DateTime.parse("2013-08-09T12:00:00.0Z"));
    assertThat(smdrl.isSmdRevoked("0000002101376042766438-65535", clock.nowUtc())).isFalse();
    clock.advanceBy(standardDays(1));
    assertThat(smdrl.isSmdRevoked("0000002101376042766438-65535", clock.nowUtc())).isTrue();
  }

  @Test
  void testOneRow() {
    SignedMarkRevocationList smdrl =
        SmdrlCsvParser.parse(
            ImmutableList.of(
                "1,2013-11-24T23:30:04.3Z",
                "smd-id,insertion-datetime",
                "0000001681375789102250-65535,2013-08-09T12:00:00.0Z"));
    assertThat(smdrl.size()).isEqualTo(1);
    assertThat(smdrl.getCreationTime()).isEqualTo(DateTime.parse("2013-11-24T23:30:04.3Z"));
    clock.setTo(DateTime.parse("2020-08-09T12:00:00.0Z"));
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", clock.nowUtc())).isTrue();
  }

  @Test
  void testEmpty() {
    SignedMarkRevocationList smdrl =
        SmdrlCsvParser.parse(
            ImmutableList.of("1,2014-11-24T23:30:04.3Z", "smd-id,insertion-datetime"));
    assertThat(smdrl.size()).isEqualTo(0);
    assertThat(smdrl.getCreationTime()).isEqualTo(DateTime.parse("2014-11-24T23:30:04.3Z"));
    clock.setTo(DateTime.parse("2020-08-09T12:00:00.0Z"));
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", clock.nowUtc())).isFalse();
  }

  @Test
  void testFail_badVersion() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SmdrlCsvParser.parse(
                    ImmutableList.of(
                        "666,2013-11-24T23:30:04.3Z",
                        "smd-id,insertion-datetime",
                        "0000001681375789102250-65535,2013-08-09T12:00:00.0Z")));
    assertThat(thrown).hasMessageThat().contains("version");
  }

  @Test
  void testFail_badHeader() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SmdrlCsvParser.parse(
                    ImmutableList.of(
                        "1,2013-11-24T23:30:04.3Z",
                        "lol,cat",
                        "0000001681375789102250-65535,2013-08-09T12:00:00.0Z")));
    assertThat(thrown).hasMessageThat().contains("header");
  }

  @Test
  void testFail_tooManyColumns() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SmdrlCsvParser.parse(
                    ImmutableList.of(
                        "1,2013-11-24T23:30:04.3Z",
                        "smd-id,insertion-datetime",
                        "0000001681375789102250-65535,haha,2013-08-09T12:00:00.0Z")));
    assertThat(thrown).hasMessageThat().contains("elements");
  }
}
