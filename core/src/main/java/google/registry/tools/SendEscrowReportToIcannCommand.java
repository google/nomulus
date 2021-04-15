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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import dagger.Lazy;
import google.registry.rde.RdeReporter;
import google.registry.tools.params.PathParameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;

/** Command to send ICANN notification that an escrow deposit was uploaded. */
@Parameters(separators = " =", commandDescription = "Send an ICANN report of an uploaded deposit.")
final class SendEscrowReportToIcannCommand implements CommandWithRemoteApi {

  @Parameter(
      description = "One or more foo-report.xml files.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  private List<Path> files;

  @Inject Lazy<RdeReporter> rdeReporter;

  @Override
  public void run() throws Exception {
    for (Path file : files) {
      rdeReporter.get().send(Files.readAllBytes(file));
      System.out.printf("Uploaded: %s\n", file);
    }
  }
}
