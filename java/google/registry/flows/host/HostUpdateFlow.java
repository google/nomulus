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
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.checkSameValuesNotAddedAndRemoved;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAllStatusesAreClientSettable;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.host.HostFlowUtils.lookupSuperordinateDomain;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.flows.host.HostFlowUtils.verifyDomainIsSameRegistrar;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ObjectAlreadyExistsException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.async.AsyncFlowEnqueuer;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.host.HostCommand.Update;
import google.registry.model.host.HostCommand.Update.AddRemove;
import google.registry.model.host.HostCommand.Update.Change;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.reporting.HistoryEntry;
import java.util.Objects;
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
 * @error {@link HostFlowUtils.HostNameTooShallowException}
 * @error {@link HostFlowUtils.InvalidHostNameException}
 * @error {@link HostFlowUtils.HostNameNotLowerCaseException}
 * @error {@link HostFlowUtils.HostNameNotNormalizedException}
 * @error {@link HostFlowUtils.HostNameNotPunyCodedException}
 * @error {@link HostFlowUtils.SuperordinateDomainDoesNotExistException}
 * @error {@link CannotAddIpToExternalHostException}
 * @error {@link CannotRemoveSubordinateHostLastIpException}
 * @error {@link HostAlreadyExistsException}
 * @error {@link RenameHostToExternalRemoveIpException}
 * @error {@link RenameHostToSubordinateRequiresIpException}
 */
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
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject AsyncFlowEnqueuer asyncFlowEnqueuer;
  @Inject DnsQueue dnsQueue;
  @Inject EppResponse.Builder responseBuilder;
  @Inject HostUpdateFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    Update command = (Update) resourceCommand;
    Change change = command.getInnerChange();
    String suppliedNewHostName = change.getFullyQualifiedHostName();
    DateTime now = ofy().getTransactionTime();
    validateHostName(targetId);
    HostResource existingHost = loadAndVerifyExistence(HostResource.class, targetId, now);
    boolean isHostRename = suppliedNewHostName != null;
    String oldHostName = targetId;
    String newHostName = firstNonNull(suppliedNewHostName, oldHostName);
    Optional<DomainResource> superordinateDomain =
        Optional.fromNullable(lookupSuperordinateDomain(validateHostName(newHostName), now));
    verifyUpdateAllowed(command, existingHost, superordinateDomain.orNull());
    if (isHostRename && loadAndGetKey(HostResource.class, newHostName, now) != null) {
      throw new HostAlreadyExistsException(newHostName);
    }
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    checkSameValuesNotAddedAndRemoved(add.getStatusValues(), remove.getStatusValues());
    checkSameValuesNotAddedAndRemoved(add.getInetAddresses(), remove.getInetAddresses());
    HostResource newHost = existingHost.asBuilder()
        .setFullyQualifiedHostName(newHostName)
        .addStatusValues(add.getStatusValues())
        .removeStatusValues(remove.getStatusValues())
        .addInetAddresses(add.getInetAddresses())
        .removeInetAddresses(remove.getInetAddresses())
        .setLastEppUpdateTime(now)
        .setLastEppUpdateClientId(clientId)
        // The superordinateDomain can be missing if the new name is external.
        // Note that the value of superordinateDomain is projected to the current time inside of
        // the lookupSuperordinateDomain(...) call above, so that it will never be stale.
        .setSuperordinateDomain(
            superordinateDomain.isPresent() ? Key.create(superordinateDomain.get()) : null)
        .setLastSuperordinateChange(superordinateDomain.isPresent() ? now : null)
        .build()
        // Rely on the host's cloneProjectedAtTime() method to handle setting of transfer data.
        .cloneProjectedAtTime(now);
    verifyHasIpsIffIsExternal(command, existingHost, newHost);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(newHost);
    // Keep the {@link ForeignKeyIndex} for this host up to date.
    if (isHostRename) {
      // Update the foreign key for the old host name and save one for the new host name.
      entitiesToSave.add(
          ForeignKeyIndex.create(existingHost, now),
          ForeignKeyIndex.create(newHost, newHost.getDeletionTime()));
      updateSuperordinateDomains(existingHost, newHost);
    }
    enqueueTasks(existingHost, newHost);
    entitiesToSave.add(historyBuilder
        .setType(HistoryEntry.Type.HOST_UPDATE)
        .setModificationTime(now)
        .setParent(Key.create(existingHost))
        .build());
    ofy().save().entities(entitiesToSave.build());
    return responseBuilder.build();
  }

  private void verifyUpdateAllowed(
      Update command, HostResource existingResource, DomainResource superordinateDomain)
      throws EppException {
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingResource);
      ImmutableSet<StatusValue> statusesToAdd = command.getInnerAdd().getStatusValues();
      ImmutableSet<StatusValue> statusesToRemove = command.getInnerRemove().getStatusValues();
      // If the resource is marked with clientUpdateProhibited, and this update does not clear that
      // status, then the update must be disallowed (unless a superuser is requesting the change).
      if (!isSuperuser
          && existingResource.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)
          && !statusesToRemove.contains(StatusValue.CLIENT_UPDATE_PROHIBITED)) {
        throw new ResourceHasClientUpdateProhibitedException();
      }
      verifyAllStatusesAreClientSettable(union(statusesToAdd, statusesToRemove));
    }
    verifyDomainIsSameRegistrar(superordinateDomain, clientId);
    verifyNoDisallowedStatuses(existingResource, DISALLOWED_STATUSES);
  }

  private void verifyHasIpsIffIsExternal(
      Update command, HostResource existingResource, HostResource newResource) throws EppException {
    boolean wasSubordinate = existingResource.isSubordinate();
    boolean wasExternal = !wasSubordinate;
    boolean willBeSubordinate = newResource.isSubordinate();
    boolean willBeExternal = !willBeSubordinate;
    boolean newResourceHasIps = !isNullOrEmpty(newResource.getInetAddresses());
    boolean commandAddsIps = !isNullOrEmpty(command.getInnerAdd().getInetAddresses());
    // These checks are order-dependent. For example a subordinate-to-external rename that adds new
    // ips should hit the first exception, whereas one that only fails to remove the existing ips
    // should hit the second.
    if (willBeExternal && commandAddsIps) {
      throw new CannotAddIpToExternalHostException();
    }
    if (wasSubordinate && willBeExternal && newResourceHasIps) {
      throw new RenameHostToExternalRemoveIpException();
    }
    if (wasExternal && willBeSubordinate && !commandAddsIps) {
      throw new RenameHostToSubordinateRequiresIpException();
    }
    if (willBeSubordinate && !newResourceHasIps) {
      throw new CannotRemoveSubordinateHostLastIpException();
    }
  }

  private void enqueueTasks(HostResource existingResource, HostResource newResource) {
    // Only update DNS for subordinate hosts. External hosts have no glue to write, so they
    // are only written as NS records from the referencing domain.
    if (existingResource.isSubordinate()) {
      dnsQueue.addHostRefreshTask(existingResource.getFullyQualifiedHostName());
    }
    // In case of a rename, there are many updates we need to queue up.
    if (((Update) resourceCommand).getInnerChange().getFullyQualifiedHostName() != null) {
      // If the renamed host is also subordinate, then we must enqueue an update to write the new
      // glue.
      if (newResource.isSubordinate()) {
        dnsQueue.addHostRefreshTask(newResource.getFullyQualifiedHostName());
      }
      // We must also enqueue updates for all domains that use this host as their nameserver so
      // that their NS records can be updated to point at the new name.
      asyncFlowEnqueuer.enqueueAsyncDnsRefresh(existingResource);
    }
  }

  private void updateSuperordinateDomains(HostResource existingResource, HostResource newResource) {
    if (existingResource.isSubordinate()
        && newResource.isSubordinate()
        && Objects.equals(
            existingResource.getSuperordinateDomain(),
            newResource.getSuperordinateDomain())) {
      ofy().save().entity(
          ofy().load().key(existingResource.getSuperordinateDomain()).now().asBuilder()
              .removeSubordinateHost(existingResource.getFullyQualifiedHostName())
              .addSubordinateHost(newResource.getFullyQualifiedHostName())
              .build());
      return;
    }
    if (existingResource.isSubordinate()) {
      ofy().save().entity(
          ofy().load().key(existingResource.getSuperordinateDomain()).now()
              .asBuilder()
              .removeSubordinateHost(existingResource.getFullyQualifiedHostName())
              .build());
    }
    if (newResource.isSubordinate()) {
      ofy().save().entity(
          ofy().load().key(newResource.getSuperordinateDomain()).now()
              .asBuilder()
              .addSubordinateHost(newResource.getFullyQualifiedHostName())
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

  /** Host rename from external to subordinate must also add an IP addresses. */
  static class RenameHostToSubordinateRequiresIpException
      extends RequiredParameterMissingException {
    public RenameHostToSubordinateRequiresIpException() {
      super("Host rename from external to subordinate must also add an IP address");
    }
  }

  /** Host rename from subordinate to external must also remove all IP addresses. */
  static class RenameHostToExternalRemoveIpException extends ParameterValueRangeErrorException {
    public RenameHostToExternalRemoveIpException() {
      super("Host rename from subordinate to external must also remove all IP addresses");
    }
  }
}
