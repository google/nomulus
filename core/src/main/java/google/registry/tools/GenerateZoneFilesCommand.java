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

import static google.registry.model.tld.Tlds.assertTldsExist;
import static google.registry.util.DateTimeUtils.toLocalDate;
import static java.time.ZoneOffset.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import google.registry.tools.params.DateParameter;
import google.registry.tools.server.GenerateZoneFilesAction;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Command to generate zone files. */
@Parameters(separators = " =", commandDescription = "Generate zone files")
final class GenerateZoneFilesCommand implements CommandWithConnection {

  @Parameter(
      description = "One or more TLDs to generate zone files for",
      required = true)
  private List<String> mainParameters;

  // Default to latest midnight that's at least 2 minutes ago.
  @Parameter(
      names = "--export_date",
      description =
          "The date to generate the file for (defaults to today, or yesterday if run "
              + "before 00:02).",
      validateWith = DateParameter.class)
  private Instant exportDate;

  @Inject Clock clock;

  private ServiceConnection connection;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws IOException {
    if (exportDate == null) {
      exportDate =
          toLocalDate(clock.now().minus(Duration.ofMinutes(2))).atStartOfDay(UTC).toInstant();
    }
    assertTldsExist(mainParameters);
    ImmutableMap<String, Object> params = ImmutableMap.of(
        "tlds", mainParameters,
        "exportTime", exportDate.toString());
    Map<String, Object> response = connection.sendJson(GenerateZoneFilesAction.PATH, params);
    System.out.println("Output files:");
    @SuppressWarnings("unchecked")
    List<String> filenames = (List<String>) response.get("filenames");
    for (String filename : filenames) {
      System.out.println(filename);
    }
  }
}
