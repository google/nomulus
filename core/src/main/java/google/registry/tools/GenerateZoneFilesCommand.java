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

import static google.registry.model.tld.Registries.assertTldsExist;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import google.registry.tools.params.DateParameter;
import google.registry.tools.server.GenerateZoneFilesAction;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;

/** Command to generate zone files. */
@Parameters(separators = " =", commandDescription = "Generate zone files")
final class GenerateZoneFilesCommand implements CommandWithConnection, CommandWithRemoteApi {

  @Parameter(
      description = "One or more TLDs to generate zone files for",
      required = true)
  private List<String> mainParameters;

  // Default to latest midnight that's at least 2 minutes ago.
  @Parameter(
      names = "--export_date",
      description = "The date to generate the file for (defaults to today, or yesterday if run "
          + "before 00:02).",
      validateWith = DateParameter.class)
  private DateTime exportDate = DateTime.now(UTC).minus(standardMinutes(2)).withTimeAtStartOfDay();

  private AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws IOException {
    assertTldsExist(mainParameters);
    ImmutableMap<String, Object> params = ImmutableMap.of(
        "tlds", mainParameters,
        "exportTime", exportDate.toString());
    Map<String, Object> response = connection.sendJson(GenerateZoneFilesAction.PATH, params);
    System.out.println(response.get("mapreduceConsoleLink"));
    System.out.println("Output files:");
    @SuppressWarnings("unchecked")
    List<String> filenames = (List<String>) response.get("filenames");
    for (String filename : filenames) {
      System.out.println(filename);
    }
  }
}
