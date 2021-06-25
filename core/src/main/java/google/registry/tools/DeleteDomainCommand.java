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
import google.registry.tools.soy.DomainDeleteSoyInfo;

/** A command to delete a domain via EPP. */
@Parameters(separators = " =", commandDescription = "Delete domain")
final class DeleteDomainCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to delete.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"--immediately"},
      description =
          "Whether to bypass grace periods and delete the domain immediately. This should only be"
              + " used in exceptional circumstances as it violates the normal expected domain"
              + " lifecycle.")
  private boolean immediately = false;

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change.",
      required = true)
  private String reason;

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.")
  private boolean requestedByRegistrar = false;

  @Override
  protected void initMutatingEppToolCommand() {
    if (immediately) {
      // Immediate deletion is accomplished using the superuser extension.
      superuser = true;
    }
    setSoyTemplate(DomainDeleteSoyInfo.getInstance(), DomainDeleteSoyInfo.DELETEDOMAIN);
    addSoyRecord(clientId, new SoyMapData(
        "domainName", domainName,
        "immediately", immediately,
        "reason", reason,
        "requestedByRegistrar", requestedByRegistrar));
  }
}
