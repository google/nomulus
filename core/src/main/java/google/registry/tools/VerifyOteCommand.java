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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.registrar.Registrar.loadByRegistrarId;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.tools.server.VerifyOteAction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * Command to verify that a registrar has passed OT&amp;E.
 *
 * <p>Outputted stats may be truncated at the point where all tests passed to avoid unnecessarily
 * loading lots of data.
 */
@Parameters(
    separators = " =",
    commandDescription = "Verify passage of OT&E for specified (or all) registrars")
final class VerifyOteCommand implements CommandWithConnection, CommandWithRemoteApi {

  @Parameter(
      description = "List of registrar names to check; must be the same names as the ones used "
          + "when creating the OT&E accounts")
  private List<String> mainParameters = new ArrayList<>();

  @Parameter(
      names = "--check_all",
      description = "Check the OT&E pass status of all active registrars")
  private boolean checkAll;

  @Parameter(
      names = "--summarize",
      description = "Only show a summary of information")
  private boolean summarize;

  private AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws IOException {
    if (RegistryEnvironment.get() != RegistryEnvironment.SANDBOX) {
      System.err.printf(
          "WARNING: Running against %s environment. Are "
              + "you sure you didn\'t mean to run this against sandbox (e.g. \"-e SANDBOX\")?%n",
          RegistryEnvironment.get());
    }
    checkArgument(
        mainParameters.isEmpty() == checkAll,
        "Must provide at least one registrar name, or supply --check_all with no names.");
    for (String registrarId : mainParameters) {
      // OT&E registrars are created with clientIDs of registrarName-[1-4], but this command is
      // passed registrarName.  So check the existence of the first persisted registrar to see if
      // the input is valid.
      checkArgumentPresent(
          loadByRegistrarId(registrarId + "-1"), "Registrar %s does not exist.", registrarId);
    }
    Collection<String> registrars =
        mainParameters.isEmpty() ? getAllRegistrarNames() : mainParameters;
    Map<String, Object> response = connection.sendJson(
        VerifyOteAction.PATH,
        ImmutableMap.of(
            "summarize", Boolean.toString(summarize),
            "registrars", new ArrayList<>(registrars)));
    System.out.println(Strings.repeat("-", 80));
    for (Entry<String, Object> registrar : response.entrySet()) {
      System.out.printf(
          summarize ? "%-20s - %s\n" : "\n=========== %s OT&E status ============\n%s\n",
          registrar.getKey(),
          registrar.getValue());
    }
    System.out.println(Strings.repeat("-", 80));
  }

  /**
   * Returns the names of all active registrars.  Finds registrar accounts with clientIds matching
   * the format used for OT&E accounts (regname-1, regname-2, etc.) and returns just the common
   * prefixes of those accounts (in this case, regname).
   */
  private ImmutableSet<String> getAllRegistrarNames() {
    return Streams.stream(Registrar.loadAll())
        .map(
            registrar -> {
              if (!registrar.isLive()) {
                return null;
              }
              String name = registrar.getRegistrarId();
              // Look for names of the form "regname-1", "regname-2", etc. and strip the -# suffix.
              String replacedName = name.replaceFirst("^(.*)-[1234]$", "$1");
              // Check if any replacement happened, and thus whether the name matches the format.
              // If it matches, provide the shortened name, and otherwise return null.
              return name.equals(replacedName) ? null : replacedName;
            })
        .filter(Objects::nonNull)
        .collect(toImmutableSet());
  }
}
