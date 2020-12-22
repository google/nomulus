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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.difference;
import static google.registry.model.EppResourceUtils.checkResourcesExist;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.tools.soy.UniformRapidSuspensionSoyInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import org.joda.time.DateTime;

/** A command to suspend a domain for the Uniform Rapid Suspension process. */
@Parameters(separators = " =",
    commandDescription = "Suspend a domain for Uniform Rapid Suspension.")
final class UniformRapidSuspensionCommand extends MutatingEppToolCommand {

  private static final ImmutableSet<String> URS_LOCKS = ImmutableSet.of(
      StatusValue.SERVER_DELETE_PROHIBITED.getXmlName(),
      StatusValue.SERVER_TRANSFER_PROHIBITED.getXmlName(),
      StatusValue.SERVER_UPDATE_PROHIBITED.getXmlName());

  /** Client id that made this change. Only recorded in the history entry. **/
  private static final String CLIENT_ID = "CharlestonRoad";

  @Parameter(
      names = {"-n", "--domain_name"},
      description = "Domain to suspend.",
      required = true)
  private String domainName;

  @Parameter(
      names = {"-h", "--hosts"},
      description = "Comma-delimited set of fully qualified host names to replace the current hosts"
          + " on the domain.")
  private List<String> newHosts = new ArrayList<>();

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

  /** Set of existing locks that need to be preserved during undo, sorted for nicer output. */
  ImmutableSortedSet<String> existingLocks;

  /** Set of existing nameservers that need to be restored during undo, sorted for nicer output. */
  ImmutableSortedSet<String> existingNameservers;

  /** Set of existing dsdata jsons that need to be restored during undo, sorted for nicer output. */
  ImmutableList<ImmutableMap<String, Object>> existingDsData;

  /** Set of status values to remove. */
  ImmutableSet<String> removeStatuses;

  @Override
  protected void initMutatingEppToolCommand() {
    superuser = true;
    DateTime now = DateTime.now(UTC);
    ImmutableSet<String> newHostsSet = ImmutableSet.copyOf(newHosts);
    Optional<DomainBase> domain = loadByForeignKey(DomainBase.class, domainName, now);
    checkArgumentPresent(domain, "Domain '%s' does not exist or is deleted", domainName);
    Set<String> missingHosts =
        difference(newHostsSet, checkResourcesExist(HostResource.class, newHosts, now));
    checkArgument(missingHosts.isEmpty(), "Hosts do not exist: %s", missingHosts);
    checkArgument(
        locksToPreserve.isEmpty() || undo,
        "Locks can only be preserved when running with --undo");
    existingNameservers = getExistingNameservers(domain.get());
    existingLocks = getExistingLocks(domain.get());
    existingDsData = getExistingDsData(domain.get());
    removeStatuses =
        (hasClientHold(domain.get()) && !undo)
            ? ImmutableSet.of(StatusValue.CLIENT_HOLD.getXmlName())
            : ImmutableSet.of();
    ImmutableSet<String> statusesToApply;
    if (undo) {
      statusesToApply =
          restoreClientHold
              ? ImmutableSet.of(StatusValue.CLIENT_HOLD.getXmlName())
              : ImmutableSet.of();
    } else {
      statusesToApply = URS_LOCKS;
    }
    setSoyTemplate(
        UniformRapidSuspensionSoyInfo.getInstance(),
        UniformRapidSuspensionSoyInfo.UNIFORMRAPIDSUSPENSION);
    addSoyRecord(
        CLIENT_ID,
        new SoyMapData(
            "domainName",
            domainName,
            "hostsToAdd",
            difference(newHostsSet, existingNameservers),
            "hostsToRemove",
            difference(existingNameservers, newHostsSet),
            "statusesToApply",
            statusesToApply,
            "statusesToRemove",
            undo ? difference(URS_LOCKS, ImmutableSet.copyOf(locksToPreserve)) : removeStatuses,
            "newDsData",
            newDsData != null ? DsRecord.convertToSoy(newDsData) : new SoyListData(),
            "reason",
            (undo ? "Undo " : "") + "Uniform Rapid Suspension"));
  }

  private ImmutableSortedSet<String> getExistingNameservers(DomainBase domain) {
    ImmutableSortedSet.Builder<String> nameservers = ImmutableSortedSet.naturalOrder();
    for (HostResource host : tm().loadByKeys(domain.getNameservers()).values()) {
      nameservers.add(host.getForeignKey());
    }
    return nameservers.build();
  }

  private ImmutableSortedSet<String> getExistingLocks(DomainBase domain) {
    ImmutableSortedSet.Builder<String> locks = ImmutableSortedSet.naturalOrder();
    for (StatusValue lock : domain.getStatusValues()) {
      if (URS_LOCKS.contains(lock.getXmlName())) {
        locks.add(lock.getXmlName());
      }
    }
    return locks.build();
  }

  private boolean hasClientHold(DomainBase domain) {
    for (StatusValue status : domain.getStatusValues()) {
      if (status == StatusValue.CLIENT_HOLD) {
        return true;
      }
    }
    return false;
  }

  private ImmutableList<ImmutableMap<String, Object>> getExistingDsData(DomainBase domain) {
    ImmutableList.Builder<ImmutableMap<String, Object>> dsDataJsons = new ImmutableList.Builder();
    HexBinaryAdapter hexBinaryAdapter = new HexBinaryAdapter();
    for (DelegationSignerData dsData : domain.getDsData()) {
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
    StringBuilder undoBuilder = new StringBuilder("UNDO COMMAND:\n\n)")
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
              .collect(toImmutableList());
      undoBuilder.append(" --dsdata ").append(Joiner.on(',').join(formattedDsRecords));
    }
    return undoBuilder.toString();
  }
}
