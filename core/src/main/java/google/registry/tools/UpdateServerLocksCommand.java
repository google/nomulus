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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.collect.Sets.union;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.tools.soy.UpdateServerLocksSoyInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A command to execute a domain check claims epp command. */
@Parameters(separators = " =",
    commandDescription = "Toggle server locks on a domain.")
final class UpdateServerLocksCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientId;

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to modify.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"-a", "--apply"},
      description = "Comma-delimited set of locks to apply (or 'all'). "
          + "Valid locks: serverDeleteProhibited, serverHold, serverRenewProhibited, "
          + "serverTransferProhibited, serverUpdateProhibited")
  private List<String> locksToApply = new ArrayList<>();

  @Parameter(
      names = {"-r", "--remove"},
      description = "Comma-delimited set of locks to remove (or 'all'). "
          + "Valid locks: same as for 'apply'.")
  private List<String> locksToRemove = new ArrayList<>();

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change. Required if registrar_request = false.")
  private String reason;

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.",
      required = true,
      arity = 1)
  private boolean requestedByRegistrar;

  private static final ImmutableSet<String> ALLOWED_VALUES = ImmutableSet.of(
      StatusValue.SERVER_DELETE_PROHIBITED.getXmlName(),
      StatusValue.SERVER_HOLD.getXmlName(),
      StatusValue.SERVER_RENEW_PROHIBITED.getXmlName(),
      StatusValue.SERVER_TRANSFER_PROHIBITED.getXmlName(),
      StatusValue.SERVER_UPDATE_PROHIBITED.getXmlName());

  private static Set<String> getStatusValuesSet(List<String> statusValues) {
    Set<String> statusValuesSet = ImmutableSet.copyOf(statusValues);
    if (statusValuesSet.contains("all")) {
      return ALLOWED_VALUES;
    }
    Set<String> badValues = difference(statusValuesSet, ALLOWED_VALUES);
    checkArgument(badValues.isEmpty(), "Invalid status values: %s", badValues);
    return statusValuesSet;
  }

  @Override
  protected void initMutatingEppToolCommand() {
    checkArgument(
        requestedByRegistrar || !isNullOrEmpty(reason),
        "A reason must be provided when a change is not requested by a registrar.");
    Set<String> valuesToApply = getStatusValuesSet(locksToApply);
    Set<String> valuesToRemove = getStatusValuesSet(locksToRemove);
    checkArgument(
        intersection(valuesToApply, valuesToRemove).isEmpty(),
        "Add and remove actions overlap");
    checkArgument(
        !union(valuesToApply, valuesToRemove).isEmpty(),
        "Add and remove actions are both empty");
    setSoyTemplate(
        UpdateServerLocksSoyInfo.getInstance(), UpdateServerLocksSoyInfo.UPDATESERVERLOCKS);
    addSoyRecord(clientId, new SoyMapData(
        "domainName", domainName,
        "locksToApply", valuesToApply,
        "locksToRemove", valuesToRemove,
        "reason", reason,
        "requestedByRegistrar", requestedByRegistrar));
  }
}
