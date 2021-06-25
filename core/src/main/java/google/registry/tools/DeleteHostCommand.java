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
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.soy.HostDeleteSoyInfo;

/** A command to delete a host via EPP. */
@Parameters(separators = " =", commandDescription = "Delete host")
final class DeleteHostCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Parameter(
      names = "--host",
      description = "Host name to delete.",
      required = true)
  private String hostName;

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change.",
      required = true)
  private String reason;

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.",
      arity = 1)
  private boolean requestedByRegistrar = false;

  @Override
  protected void initMutatingEppToolCommand() {
    setSoyTemplate(HostDeleteSoyInfo.getInstance(), HostDeleteSoyInfo.DELETEHOST);
    addSoyRecord(clientId, new SoyMapData(
        "hostName", hostName,
        "reason", reason,
        "requestedByRegistrar", requestedByRegistrar));
  }
}
