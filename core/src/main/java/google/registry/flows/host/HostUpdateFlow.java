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

package google.registry.flows.host;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Sets.union;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.checkSameValuesNotAddedAndRemoved;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAllStatusesAreClientSettable;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.host.HostFlowUtils.lookupSuperordinateDomain;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.flows.host.HostFlowUtils.verifySuperordinateDomainNotInPendingDelete;
import static google.registry.flows.host.HostFlowUtils.verifySuperordinateDomainOwnership;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetKey;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_UPDATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.google.common.collect.ImmutableSet;
import google.registry.batch.AsyncTaskEnqueuer;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ObjectAlreadyExistsException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.host.HostCommand.Update;
import google.registry.model.host.HostCommand.Update.AddRemove;
import google.registry.model.host.HostCommand.Update.Change;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.persistence.VKey;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that updates a host.
 *
 * <p>Hosts can be "external", or "internal" (also known as "in bailiwick"). Internal hosts are
 * those that are under a top level domain within this registry, and external hosts are all other
 * hosts. Internal hosts must have at least one IP address associated with them, whereas external
 * hosts cannot have any.
 *
 * <p>This flow allows changing a host name, and adding or removing IP addresses to hosts. When
 * a host is renamed from internal to external all IP addresses must be simultaneously removed, and
 * when it is renamed from external to internal at least one must be added. If the host is renamed
 * or IP addresses are added, tasks are enqueued to update DNS accordingly.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException}
 * @error {@link google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link HostFlowUtils.HostDomainNotOwnedException}
 * @error {@link HostFlowUtils.HostNameNotLowerCaseException}
 * @error {@link HostFlowUtils.HostNameNotNormalizedException}
 * @error {@link HostFlowUtils.HostNameNotPunyCodedException}
 * @error {@link HostFlowUtils.HostNameTooLongException}
 * @error {@link HostFlowUtils.HostNameTooShallowException}
 * @error {@link HostFlowUtils.InvalidHostNameException}
 * @error {@link HostFlowUtils.SuperordinateDomainDoesNotExistException}
 * @error {@link HostFlowUtils.SuperordinateDomainInPendingDeleteException}
 * @error {@link CannotAddIpToExternalHostException}
 * @error {@link CannotRemoveSubordinateHostLastIpException}
 * @error {@link CannotRenameExternalHostException}
 * @error {@link HostAlreadyExistsException}
 * @error {@link RenameHostToExternalRemoveIpException}
 */
@ReportingSpec(ActivityReportField.HOST_UPDATE)
public final class HostUpdateFlow implements TransactionalFlow {

  /**
   * Note that CLIENT_UPDATE_PROHIBITED is intentionally not in this list. This is because it
   * requires special checking, since you must be able to clear the status off the object with an
   * update.
   */
  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_UPDATE_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HostHistory.Builder historyBuilder;
  @Inject AsyncTaskEnqueuer asyncTaskEnqueuer;
  @Inject DnsQueue dnsQueue;
  @Inject EppResponse.Builder responseBuilder;
  @Inject HostUpdateFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    Update command = (Update) resourceCommand;
    Change change = command.getInnerChange();
    String suppliedNewHostName = change.getFullyQualifiedHostName();
    DateTime now = tm().getTransactionTime();
    validateHostName(targetId);
    HostResource existingHost = loadAndVerifyExistence(HostResource.class, targetId, now);
    boolean isHostRename = suppliedNewHostName != null;
    String oldHostName = targetId;
    String newHostName = firstNonNull(suppliedNewHostName, oldHostName);
    DomainBase oldSuperordinateDomain =
        existingHost.isSubordinate()
            ? tm().loadByKey(existingHost.getSuperordinateDomain()).cloneProjectedAtTime(now)
            : null;
    // Note that lookupSuperordinateDomain calls cloneProjectedAtTime on the domain for us.
    Optional<DomainBase> newSuperordinateDomain =
        lookupSuperordinateDomain(validateHostName(newHostName), now);
    verifySuperordinateDomainNotInPendingDelete(newSuperordinateDomain.orElse(null));
    EppResource owningResource = firstNonNull(oldSuperordinateDomain, existingHost);
    verifyUpdateAllowed(
        command, existingHost, newSuperordinateDomain.orElse(null), owningResource, isHostRename);
    if (isHostRename && loadAndGetKey(HostResource.class, newHostName, now) != null) {
      throw new HostAlreadyExistsException(newHostName);
    }
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    checkSameValuesNotAddedAndRemoved(add.getStatusValues(), remove.getStatusValues());
    checkSameValuesNotAddedAndRemoved(add.getInetAddresses(), remove.getInetAddresses());
    VKey<DomainBase> newSuperordinateDomainKey =
        newSuperordinateDomain.map(DomainBase::createVKey).orElse(null);
    // If the superordinateDomain field is changing, set the lastSuperordinateChange to now.
    DateTime lastSuperordinateChange =
        Objects.equals(newSuperordinateDomainKey, existingHost.getSuperordinateDomain())
            ? existingHost.getLastSuperordinateChange()
            : now;
    // Compute afresh the last transfer time to handle any superordinate domain transfer that may
    // have just completed.  This is only critical for updates that rename a host away from its
    // current superordinate domain, where we must "freeze" the last transfer time, but it's easiest
    // to just update it unconditionally.
    DateTime lastTransferTime = existingHost.computeLastTransferTime(oldSuperordinateDomain);
    // Copy the clientId onto the host. This is only really needed when the host will be external,
    // since external hosts store their own clientId. For subordinate hosts the canonical clientId
    // comes from the superordinate domain, but we might as well update the persisted value. For
    // non-superusers this is the flow clientId, but for superusers it might not be, so compute it.
    String newPersistedRegistrarId =
        newSuperordinateDomain.isPresent()
            ? newSuperordinateDomain.get().getCurrentSponsorRegistrarId()
            : owningResource.getPersistedCurrentSponsorRegistrarId();
    HostResource newHost =
        existingHost
            .asBuilder()
            .setHostName(newHostName)
            .addStatusValues(add.getStatusValues())
            .removeStatusValues(remove.getStatusValues())
            .addInetAddresses(add.getInetAddresses())
            .removeInetAddresses(remove.getInetAddresses())
            .setLastEppUpdateTime(now)
            .setLastEppUpdateRegistrarId(registrarId)
            .setSuperordinateDomain(newSuperordinateDomainKey)
            .setLastSuperordinateChange(lastSuperordinateChange)
            .setLastTransferTime(lastTransferTime)
            .setPersistedCurrentSponsorRegistrarId(newPersistedRegistrarId)
            .build();
    verifyHasIpsIffIsExternal(command, existingHost, newHost);
    ImmutableSet.Builder<ImmutableObject> entitiesToInsert = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<ImmutableObject> entitiesToUpdate = new ImmutableSet.Builder<>();
    entitiesToUpdate.add(newHost);
    // Keep the {@link ForeignKeyIndex} for this host up to date.
    if (isHostRename) {
      // Update the foreign key for the old host name and save one for the new host name.
      entitiesToUpdate.add(ForeignKeyIndex.create(existingHost, now));
      entitiesToUpdate.add(ForeignKeyIndex.create(newHost, newHost.getDeletionTime()));
      updateSuperordinateDomains(existingHost, newHost);
    }
    enqueueTasks(existingHost, newHost);
    entitiesToInsert.add(historyBuilder.setType(HOST_UPDATE).setHost(newHost).build());
    tm().updateAll(entitiesToUpdate.build());
    tm().insertAll(entitiesToInsert.build());
    return responseBuilder.build();
  }

  private void verifyUpdateAllowed(
      Update command,
      HostResource existingHost,
      DomainBase newSuperordinateDomain,
      EppResource owningResource,
      boolean isHostRename)
          throws EppException {
    if (!isSuperuser) {
      // Verify that the host belongs to this registrar, either directly or because it is currently
      // subordinate to a domain owned by this registrar.
      verifyResourceOwnership(registrarId, owningResource);
      if (isHostRename && !existingHost.isSubordinate()) {
        throw new CannotRenameExternalHostException();
      }
      // Verify that the new superordinate domain belongs to this registrar.
      verifySuperordinateDomainOwnership(registrarId, newSuperordinateDomain);
      ImmutableSet<StatusValue> statusesToAdd = command.getInnerAdd().getStatusValues();
      ImmutableSet<StatusValue> statusesToRemove = command.getInnerRemove().getStatusValues();
      // If the resource is marked with clientUpdateProhibited, and this update does not clear that
      // status, then the update must be disallowed.
      if (existingHost.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)
          && !statusesToRemove.contains(StatusValue.CLIENT_UPDATE_PROHIBITED)) {
        throw new ResourceHasClientUpdateProhibitedException();
      }
      verifyAllStatusesAreClientSettable(union(statusesToAdd, statusesToRemove));
    }
    verifyNoDisallowedStatuses(existingHost, DISALLOWED_STATUSES);
  }

  private void verifyHasIpsIffIsExternal(
      Update command, HostResource existingHost, HostResource newHost) throws EppException {
    boolean wasSubordinate = existingHost.isSubordinate();
    boolean willBeSubordinate = newHost.isSubordinate();
    boolean willBeExternal = !willBeSubordinate;
    boolean newHostHasIps = !isNullOrEmpty(newHost.getInetAddresses());
    boolean commandAddsIps = !isNullOrEmpty(command.getInnerAdd().getInetAddresses());
    // These checks are order-dependent. For example a subordinate-to-external rename that adds new
    // ips should hit the first exception, whereas one that only fails to remove the existing ips
    // should hit the second.
    if (willBeExternal && commandAddsIps) {
      throw new CannotAddIpToExternalHostException();
    }
    if (wasSubordinate && willBeExternal && newHostHasIps) {
      throw new RenameHostToExternalRemoveIpException();
    }
    if (willBeSubordinate && !newHostHasIps) {
      throw new CannotRemoveSubordinateHostLastIpException();
    }
  }

  private void enqueueTasks(HostResource existingHost, HostResource newHost) {
    // Only update DNS for subordinate hosts. External hosts have no glue to write, so they
    // are only written as NS records from the referencing domain.
    if (existingHost.isSubordinate()) {
      dnsQueue.addHostRefreshTask(existingHost.getHostName());
    }
    // In case of a rename, there are many updates we need to queue up.
    if (((Update) resourceCommand).getInnerChange().getFullyQualifiedHostName() != null) {
      // If the renamed host is also subordinate, then we must enqueue an update to write the new
      // glue.
      if (newHost.isSubordinate()) {
        dnsQueue.addHostRefreshTask(newHost.getHostName());
      }
      // We must also enqueue updates for all domains that use this host as their nameserver so
      // that their NS records can be updated to point at the new name.
      asyncTaskEnqueuer.enqueueAsyncDnsRefresh(existingHost, tm().getTransactionTime());
    }
  }

  private static void updateSuperordinateDomains(HostResource existingHost, HostResource newHost) {
    if (existingHost.isSubordinate()
        && newHost.isSubordinate()
        && Objects.equals(
            existingHost.getSuperordinateDomain(), newHost.getSuperordinateDomain())) {
      tm().put(
              tm().loadByKey(existingHost.getSuperordinateDomain())
                  .asBuilder()
                  .removeSubordinateHost(existingHost.getHostName())
                  .addSubordinateHost(newHost.getHostName())
                  .build());
      return;
    }
    if (existingHost.isSubordinate()) {
      tm().put(
              tm().loadByKey(existingHost.getSuperordinateDomain())
                  .asBuilder()
                  .removeSubordinateHost(existingHost.getHostName())
                  .build());
    }
    if (newHost.isSubordinate()) {
      tm().put(
              tm().loadByKey(newHost.getSuperordinateDomain())
                  .asBuilder()
                  .addSubordinateHost(newHost.getHostName())
                  .build());
    }
  }

  /** Host with specified name already exists. */
  static class HostAlreadyExistsException extends ObjectAlreadyExistsException {
    public HostAlreadyExistsException(String hostName) {
      super(String.format("Object with given ID (%s) already exists", hostName));
    }
  }

  /** Cannot add IP addresses to an external host. */
  static class CannotAddIpToExternalHostException extends ParameterValueRangeErrorException {
    public CannotAddIpToExternalHostException() {
      super("Cannot add IP addresses to external hosts");
    }
  }

  /** Cannot remove all IP addresses from a subordinate host. */
  static class CannotRemoveSubordinateHostLastIpException
      extends StatusProhibitsOperationException {
    public CannotRemoveSubordinateHostLastIpException() {
      super("Cannot remove all IP addresses from a subordinate host");
    }
  }

  /** Cannot rename an external host. */
  static class CannotRenameExternalHostException extends StatusProhibitsOperationException {
    public CannotRenameExternalHostException() {
      super("Cannot rename an external host");
    }
  }

  /** Host rename from subordinate to external must also remove all IP addresses. */
  static class RenameHostToExternalRemoveIpException extends ParameterValueRangeErrorException {
    public RenameHostToExternalRemoveIpException() {
      super("Host rename from subordinate to external must also remove all IP addresses");
    }
  }
}
