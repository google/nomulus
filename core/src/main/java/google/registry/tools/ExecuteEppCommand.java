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

package google.registry.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** A command to execute an arbitrary epp command from file or stdin. */
@Parameters(separators = " =", commandDescription = "Execute an epp command")
final class ExecuteEppCommand extends MutatingEppToolCommand {
  @Parameter(description = "Epp command filename")
  private List<String> mainParameters = new ArrayList<>();

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Override
  protected void initMutatingEppToolCommand() throws IOException {
    if (mainParameters.isEmpty()) {
      addXmlCommand(clientId, CharStreams.toString(new InputStreamReader(System.in, UTF_8)));
    } else {
      for (String command : mainParameters) {
        addXmlCommand(clientId, Files.asCharSource(new File(command), UTF_8).read());
      }
    }
  }
}
