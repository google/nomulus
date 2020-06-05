// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.gcs.GcsUtils;
import google.registry.testing.TestDataHelper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.joda.time.LocalDate;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Spec11RegistrarThreatMatchesParser}. */
@RunWith(JUnit4.class)
public class Spec11RegistrarThreatMatchesParserTest {

  private static final String TODAY = "2018-07-21";
  private static final String YESTERDAY = "2018-07-20";

  private final GcsUtils gcsUtils = mock(GcsUtils.class);
  private final Spec11RegistrarThreatMatchesParser parser =
      new Spec11RegistrarThreatMatchesParser(gcsUtils, "test-bucket");

  @Before
  public void setUp() {
    setupFile("spec11_fake_report", TODAY);
  }

  @Test
  public void testSuccess_retrievesReport() throws Exception {
    assertThat(parser.getRegistrarThreatMatches(LocalDate.parse(TODAY)))
        .isEqualTo(sampleThreatMatches());
  }

  @Test
  public void testFindPrevious_exists() throws Exception {
    setupFile("spec11_fake_report_previous_day", YESTERDAY);
    assertThat(parser.getPreviousDateWithMatches(LocalDate.parse(TODAY)))
        .hasValue(LocalDate.parse(YESTERDAY));
  }

  @Test
  public void testFindPrevious_notFound() {
    assertThat(parser.getPreviousDateWithMatches(LocalDate.parse(TODAY))).isEmpty();
  }

  @Test
  public void testFindPrevious_olderThanYesterdayFound() throws Exception {
    setupFile("spec11_fake_report_previous_day", "2018-07-14");

    assertThat(parser.getPreviousDateWithMatches(LocalDate.parse(TODAY)))
        .hasValue(LocalDate.parse("2018-07-14"));
  }

  @Test
  public void testSuccess_ignoreExtraFields() throws Exception {
    ThreatMatch objectWithExtraFields =
        ThreatMatch.fromJSON(
            new JSONObject(
                ImmutableMap.of(
                    "threatType", "MALWARE",
                    "platformType", "ANY_PLATFORM",
                    "threatEntryMetaData", "NONE",
                    "fullyQualifiedDomainName", "c.com")));
    ThreatMatch objectWithoutExtraFields =
        ThreatMatch.fromJSON(
            new JSONObject(
                ImmutableMap.of(
                    "threatType", "MALWARE",
                    "fullyQualifiedDomainName", "c.com")));

    assertThat(objectWithExtraFields).isEqualTo(objectWithoutExtraFields);
  }

  /** The expected contents of the sample spec11 report file */
  static ImmutableSet<RegistrarThreatMatches> sampleThreatMatches() throws Exception {
    return ImmutableSet.of(getMatchA(), getMatchB());
  }

  static RegistrarThreatMatches getMatchA() throws Exception {
    return RegistrarThreatMatches.create(
        "TheRegistrar",
        ImmutableList.of(
            ThreatMatch.fromJSON(
                new JSONObject(
                    ImmutableMap.of(
                        "threatType", "MALWARE",
                        "fullyQualifiedDomainName", "a.com")))));
  }

  static RegistrarThreatMatches getMatchB() throws Exception {
    return RegistrarThreatMatches.create(
        "NewRegistrar",
        ImmutableList.of(
            ThreatMatch.fromJSON(
                new JSONObject(
                    ImmutableMap.of(
                        "threatType", "MALWARE",
                        "fullyQualifiedDomainName", "b.com"))),
            ThreatMatch.fromJSON(
                new JSONObject(
                    ImmutableMap.of(
                        "threatType", "MALWARE",
                        "fullyQualifiedDomainName", "c.com")))));
  }

  private void setupFile(String fileWithContent, String fileDate) {
    GcsFilename gcsFilename =
        new GcsFilename(
            "test-bucket",
            String.format("icann/spec11/2018-07/SPEC11_MONTHLY_REPORT_%s", fileDate));
    when(gcsUtils.existsAndNotEmpty(gcsFilename)).thenReturn(true);
    when(gcsUtils.openInputStream(gcsFilename))
        .thenAnswer(
            (args) ->
                new ByteArrayInputStream(
                    loadFile(fileWithContent).getBytes(StandardCharsets.UTF_8)));
  }

  private static String loadFile(String filename) {
    return TestDataHelper.loadFile(Spec11EmailUtils.class, filename);
  }
}
