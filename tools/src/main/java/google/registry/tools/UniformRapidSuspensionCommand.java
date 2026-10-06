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
import static com.google.common.collect.Sets.difference;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.time.ZoneOffset.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.Period;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppExtensions;
import google.registry.model.eppinput.EppInput;
import google.registry.model.host.Host;
import google.registry.tools.params.NameserversParameter;
import jakarta.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A command to suspend a domain for the Uniform Rapid Suspension process. */
@Parameters(
    separators = " =",
    commandDescription = "Suspend a domain for Uniform Rapid Suspension.")
final class UniformRapidSuspensionCommand extends MutatingEppToolCommand {

  private static final ImmutableSet<String> URS_LOCKS =
      ImmutableSet.of(
          StatusValue.SERVER_DELETE_PROHIBITED.getXmlName(),
          StatusValue.SERVER_TRANSFER_PROHIBITED.getXmlName(),
          StatusValue.SERVER_UPDATE_PROHIBITED.getXmlName());

  /** Client id that made this change. Only recorded in the history entry. * */
  private static final String CLIENT_ID = "CharlestonRoad";

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to suspend.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"-h", "--hosts"},
      description =
          "Comma-delimited set of fully qualified host names to replace the current hosts"
              + " on the domain.",
      listConverter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  private Set<String> newHosts = new HashSet<>();

  @Parameter(
      names = {"-s", "--dsdata"},
      description =
          "Comma-delimited set of dsdata to replace the current dsdata on the domain, "
              + "Each DS record is given as <keyTag> <alg> <digestType> <digest>, in order, as it "
              + "appears in the Zonefile.",
      converter = DsRecord.Converter.class)
  private List<DsRecord> newDsData;

  @Parameter(
      names = {"-p", "--locks_to_preserve"},
      description =
          "Comma-delimited set of locks to preserve (only valid with --undo). Valid "
              + "locks: serverDeleteProhibited, serverTransferProhibited, serverUpdateProhibited")
  private List<String> locksToPreserve = new ArrayList<>();

  @Parameter(
      names = {"--restore_client_hold"},
      description =
          "Restores a CLIENT_HOLD status that was previously removed for a URS suspension (only "
              + "valid with --undo).")
  private boolean restoreClientHold;

  @Parameter(
      names = {"--undo"},
      description = "Flag indicating that is is an undo command, which removes locks.")
  private boolean undo;

  @Parameter(
      names = {"--renew_one_year"},
      required = true,
      description = "Flag indicating whether or not the domain will be renewed for a year.",
      arity = 1)
  private boolean renewOneYear;

  /** Set of existing locks that need to be preserved during undo, sorted for nicer output. */
  ImmutableSortedSet<String> existingLocks;

  /** Set of existing nameservers that need to be restored during undo, sorted for nicer output. */
  ImmutableSortedSet<String> existingNameservers;

  /** Set of existing dsdata jsons that need to be restored during undo, sorted for nicer output. */
  ImmutableList<ImmutableMap<String, Object>> existingDsData;

  /** Set of status values to remove. */
  ImmutableSet<String> removeStatuses;

  @Override
  protected void initMutatingEppToolCommand()
      throws ResourceFlowUtils.ResourceDoesNotExistException {
    superuser = true;
    Instant now = clock.now();
    Domain domain = ResourceFlowUtils.loadAndVerifyExistence(Domain.class, domainName, now);
    ImmutableSet<String> missingHosts =
        ImmutableSet.copyOf(
            difference(newHosts, ForeignKeyUtils.loadKeys(Host.class, newHosts, now).keySet()));
    checkArgument(missingHosts.isEmpty(), "Hosts do not exist: %s", missingHosts);
    checkArgument(
        locksToPreserve.isEmpty() || undo, "Locks can only be preserved when running with --undo");
    existingNameservers = getExistingNameservers(domain);
    existingLocks = getExistingLocks(domain);
    existingDsData = getExistingDsData(domain);
    removeStatuses =
        (hasClientHold(domain) && !undo)
            ? ImmutableSet.of(StatusValue.CLIENT_HOLD.getXmlName())
            : ImmutableSet.of();
    ImmutableSet<String> statusesToApply;
    if (undo) {
      statusesToApply =
          restoreClientHold
              ? ImmutableSet.of(StatusValue.CLIENT_HOLD.getXmlName())
              : ImmutableSet.of();
    } else {
      statusesToApply = ImmutableSet.copyOf(difference(URS_LOCKS, existingLocks));
    }

    // trigger renew flow
    if (renewOneYear) {
      DomainCommand.Renew.Builder renewBuilder =
          new DomainCommand.Renew.Builder()
              .setTargetId(domain.getDomainName())
              .setPeriod(Period.create(1, Period.Unit.YEARS))
              .setCurrentExpirationDate(
                  domain.getRegistrationExpirationTime().atZone(UTC).toLocalDate());

      addEppInput(
          CLIENT_ID,
          EppInput.create(
                  EppInput.Renew.create(renewBuilder.build()),
                  EppExtensions.toolMetadata(
                      (undo ? "Undo " : "") + "Uniform Rapid Suspension", false))
              .withClTrid("RegistryTool"));
    }

    // trigger update flow
    DomainCommand.Update.Builder updateBuilder =
        new DomainCommand.Update.Builder().setTargetId(domainName);

    DomainCommand.Update.DomainAddRemove.Builder addBuilder =
        new DomainCommand.Update.DomainAddRemove.Builder();
    DomainCommand.Update.DomainAddRemove.Builder removeBuilder =
        new DomainCommand.Update.DomainAddRemove.Builder();
    boolean hasAdd = false;
    boolean hasRemove = false;

    if (!statusesToApply.isEmpty()) {
      addBuilder.setStatusValues(
          statusesToApply.stream()
              .map(StatusValue::fromXmlName)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())));
      hasAdd = true;
    }

    ImmutableSet<String> statusesToRemove =
        undo
            ? ImmutableSet.copyOf(difference(URS_LOCKS, ImmutableSet.copyOf(locksToPreserve)))
            : removeStatuses;

    if (!statusesToRemove.isEmpty()) {
      removeBuilder.setStatusValues(
          statusesToRemove.stream()
              .map(StatusValue::fromXmlName)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())));
      hasRemove = true;
    }

    ImmutableSet<String> addNameservers =
        ImmutableSet.copyOf(difference(newHosts, existingNameservers));
    if (!addNameservers.isEmpty()) {
      addBuilder.setNameserverHostNames(ImmutableSortedSet.copyOf(addNameservers));
      hasAdd = true;
    }

    ImmutableSet<String> removeNameservers =
        ImmutableSet.copyOf(difference(existingNameservers, newHosts));
    if (!removeNameservers.isEmpty()) {
      removeBuilder.setNameserverHostNames(ImmutableSortedSet.copyOf(removeNameservers));
      hasRemove = true;
    }

    if (hasAdd) {
      updateBuilder.setInnerAdd(addBuilder.build());
    }
    if (hasRemove) {
      updateBuilder.setInnerRemove(removeBuilder.build());
    }

    addEppInput(
        CLIENT_ID,
        EppInput.create(
                EppInput.Update.create(updateBuilder.build()),
                EppExtensions.secDnsUpdate(
                    newDsData == null
                        ? ImmutableSet.of()
                        : newDsData.stream().map(DsRecord::toDsData).collect(toImmutableSet()),
                    ImmutableSet.of(),
                    true),
                EppExtensions.updateSuperuser(undo),
                EppExtensions.toolMetadata(
                    (undo ? "Undo " : "") + "Uniform Rapid Suspension", false))
            .withClTrid("RegistryTool"));
  }

  /** Returns the set of existing nameservers for the specified domain. */
  private ImmutableSortedSet<String> getExistingNameservers(Domain domain) {
    ImmutableSortedSet.Builder<String> nameservers = ImmutableSortedSet.naturalOrder();
    for (Host host : tm().transact(() -> tm().loadByKeys(domain.getNameservers()).values())) {
      nameservers.add(host.getForeignKey());
    }
    return nameservers.build();
  }

  /** Returns the set of existing URS-related locks for the specified domain. */
  private ImmutableSortedSet<String> getExistingLocks(Domain domain) {
    ImmutableSortedSet.Builder<String> locks = ImmutableSortedSet.naturalOrder();
    for (StatusValue lock : domain.getStatusValues()) {
      if (URS_LOCKS.contains(lock.getXmlName())) {
        locks.add(lock.getXmlName());
      }
    }
    return locks.build();
  }

  /** Returns whether the specified domain has a CLIENT_HOLD status. */
  private boolean hasClientHold(Domain domain) {
    for (StatusValue status : domain.getStatusValues()) {
      if (status == StatusValue.CLIENT_HOLD) {
        return true;
      }
    }
    return false;
  }

  /** Returns a list of the existing DS records for the specified domain as JSON-like maps. */
  private ImmutableList<ImmutableMap<String, Object>> getExistingDsData(Domain domain) {
    ImmutableList.Builder<ImmutableMap<String, Object>> dsDataJsons = new ImmutableList.Builder();
    HexBinaryAdapter hexBinaryAdapter = new HexBinaryAdapter();
    for (DomainDsData dsData : domain.getDsData()) {
      dsDataJsons.add(
          ImmutableMap.of(
              "keyTag", dsData.getKeyTag(),
              "algorithm", dsData.getAlgorithm(),
              "digestType", dsData.getDigestType(),
              "digest", hexBinaryAdapter.marshal(dsData.getDigest())));
    }
    return dsDataJsons.build();
  }

  @Override
  protected String postExecute() {
    if (undo) {
      return "";
    }
    StringBuilder undoBuilder =
        new StringBuilder("UNDO COMMAND:\n\n")
            .append("nomulus -e ")
            .append(RegistryToolEnvironment.get())
            .append(" uniform_rapid_suspension --undo --domain_name ")
            .append(domainName);
    if (!existingNameservers.isEmpty()) {
      undoBuilder.append(" --hosts ").append(Joiner.on(',').join(existingNameservers));
    }
    if (!existingLocks.isEmpty()) {
      undoBuilder.append(" --locks_to_preserve ").append(Joiner.on(',').join(existingLocks));
    }
    if (removeStatuses.contains(StatusValue.CLIENT_HOLD.getXmlName())) {
      undoBuilder.append(" --restore_client_hold");
    }
    if (!existingDsData.isEmpty()) {
      ImmutableList<String> formattedDsRecords =
          existingDsData.stream()
              .map(
                  rec ->
                      String.format(
                          "%s %s %s %s",
                          rec.get("keyTag"),
                          rec.get("algorithm"),
                          rec.get("digestType"),
                          rec.get("digest")))
              .sorted()
              .collect(ImmutableList.toImmutableList());
      undoBuilder.append(" --dsdata ").append(Joiner.on(',').join(formattedDsRecords));
    }
    return undoBuilder.toString();
  }
}
