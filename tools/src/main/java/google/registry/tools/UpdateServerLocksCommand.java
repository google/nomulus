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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.collect.Sets.union;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.model.domain.DomainCommand;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppExtensions;
import google.registry.model.eppinput.EppInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A command to execute a domain check claims epp command. */
@Parameters(separators = " =",
    commandDescription = "Toggle server locks on a domain.")
final class UpdateServerLocksCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to lock/unlock.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"--client"},
      description = "Client ID to use for the EPP command.",
      required = true)
  private String clientId;

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change.")
  private String reason;

  @Parameter(
      names = {"--apply"},
      description = "Statuses to apply. Use \"all\" to apply all server locks.")
  private List<String> locksToApply = new ArrayList<>();

  @Parameter(
      names = {"--remove"},
      description = "Statuses to remove. Use \"all\" to remove all server locks.")
  private List<String> locksToRemove = new ArrayList<>();

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.",
      arity = 1)
  private Boolean requestedByRegistrar;

  private static final ImmutableSet<String> ALLOWED_VALUES =
      ImmutableSet.of(
          StatusValue.SERVER_DELETE_PROHIBITED.getXmlName(),
          StatusValue.SERVER_HOLD.getXmlName(),
          StatusValue.SERVER_RENEW_PROHIBITED.getXmlName(),
          StatusValue.SERVER_TRANSFER_PROHIBITED.getXmlName(),
          StatusValue.SERVER_UPDATE_PROHIBITED.getXmlName());

  private static ImmutableSet<String> getStatusValuesSet(List<String> statusValues) {
    ImmutableSet<String> statusValuesSet = ImmutableSet.copyOf(statusValues);
    if (statusValuesSet.contains("all")) {
      return ALLOWED_VALUES;
    }
    ImmutableSet<String> badValues =
        ImmutableSet.copyOf(difference(statusValuesSet, ALLOWED_VALUES));
    checkArgument(badValues.isEmpty(), "Invalid status values: %s", badValues);
    return statusValuesSet;
  }

  @Override
  protected void initMutatingEppToolCommand() {
    if (requestedByRegistrar == null) {
      throw new ParameterException("--registrar_request must be specified");
    }
    checkArgument(
        requestedByRegistrar || !isNullOrEmpty(reason),
        "A reason must be provided when a change is not requested by a registrar.");
    ImmutableSet<String> valuesToApply = getStatusValuesSet(locksToApply);
    ImmutableSet<String> valuesToRemove = getStatusValuesSet(locksToRemove);
    checkArgument(
        intersection(valuesToApply, valuesToRemove).isEmpty(),
        "Add and remove actions overlap");
    checkArgument(
        !union(valuesToApply, valuesToRemove).isEmpty(),
        "Add and remove actions are both empty");

    DomainCommand.Update.Builder updateBuilder =
        new DomainCommand.Update.Builder().setTargetId(domainName);

    if (!valuesToApply.isEmpty()) {
      updateBuilder.setInnerAdd(
          new DomainCommand.Update.DomainAddRemove.Builder()
              .setStatusValues(
                  valuesToApply.stream()
                      .map(StatusValue::fromXmlName)
                      .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())))
              .build());
    }
    if (!valuesToRemove.isEmpty()) {
      updateBuilder.setInnerRemove(
          new DomainCommand.Update.DomainAddRemove.Builder()
              .setStatusValues(
                  valuesToRemove.stream()
                      .map(StatusValue::fromXmlName)
                      .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())))
              .build());
    }

    addEppInput(
        clientId,
        EppInput.create(
                EppInput.Update.create(updateBuilder.build()),
                EppExtensions.toolMetadata(reason, requestedByRegistrar))
            .withClTrid("RegistryTool"));
  }
}
