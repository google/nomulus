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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import google.registry.beam.spec11.Spec11Pipeline;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.LocalDate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parser to retrieve which registrar-threat matches we should notify via email */
public class Spec11RegistrarThreatMatchesParser {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GcsUtils gcsUtils;
  private final String reportingBucket;

  @Inject
  public Spec11RegistrarThreatMatchesParser(
      GcsUtils gcsUtils, @Config("reportingBucket") String reportingBucket) {
    this.gcsUtils = gcsUtils;
    this.reportingBucket = reportingBucket;
  }

  /**
   * Gets the entire set of registrar:set-of-threat-match pairings from the most recent report file
   * in GCS.
   */
  public ImmutableSet<RegistrarThreatMatches> getRegistrarThreatMatches(LocalDate date)
      throws IOException, JSONException {
    return getFromFile(getGcsFilename(date));
  }

  /** Returns registrar:set-of-threat-match pairings from the file, or empty if it doesn't exist. */
  public ImmutableSet<RegistrarThreatMatches> getFromFile(GcsFilename spec11ReportFilename)
      throws IOException {
    if (!gcsUtils.existsAndNotEmpty(spec11ReportFilename)) {
      return ImmutableSet.of();
    }
    try (InputStream in = gcsUtils.openInputStream(spec11ReportFilename);
        InputStreamReader isr = new InputStreamReader(in, UTF_8)) {
      // Skip the header at line 0
      return Splitter.on("\n")
          .omitEmptyStrings()
          .splitToStream(CharStreams.toString(isr))
          .skip(1)
          .map(this::parseRegistrarThreatMatch)
          .collect(toImmutableSet());
    }
  }

  public Optional<LocalDate> getPreviousDateWithMatches(LocalDate date) {
    LocalDate yesterday = date.minusDays(1);
    GcsFilename gcsFilename = getGcsFilename(yesterday);
    if (gcsUtils.existsAndNotEmpty(gcsFilename)) {
      return Optional.of(yesterday);
    }
    logger.atWarning().log("Could not find previous file from date %s", yesterday);

    for (LocalDate dateToCheck = yesterday.minusDays(1);
        !dateToCheck.isBefore(date.minusMonths(1));
        dateToCheck = dateToCheck.minusDays(1)) {
      gcsFilename = getGcsFilename(dateToCheck);
      if (gcsUtils.existsAndNotEmpty(gcsFilename)) {
        return Optional.of(dateToCheck);
      }
    }
    return Optional.empty();
  }

  private GcsFilename getGcsFilename(LocalDate localDate) {
    return new GcsFilename(reportingBucket, Spec11Pipeline.getSpec11ReportFilePath(localDate));
  }

  private RegistrarThreatMatches parseRegistrarThreatMatch(String line) throws JSONException {
    JSONObject reportJSON = new JSONObject(line);
    String clientId = reportJSON.getString(Spec11Pipeline.REGISTRAR_CLIENT_ID_FIELD);
    JSONArray threatMatchesArray = reportJSON.getJSONArray(Spec11Pipeline.THREAT_MATCHES_FIELD);
    ImmutableList.Builder<ThreatMatch> threatMatches = ImmutableList.builder();
    for (int i = 0; i < threatMatchesArray.length(); i++) {
      threatMatches.add(ThreatMatch.fromJSON(threatMatchesArray.getJSONObject(i)));
    }
    return RegistrarThreatMatches.create(clientId, threatMatches.build());
  }
}
