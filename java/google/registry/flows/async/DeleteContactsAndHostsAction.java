// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.async;

import static com.google.appengine.api.taskqueue.QueueConstants.maxLeaseCount;
import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.math.IntMath.divide;
import static com.googlecode.objectify.Key.getKind;
import static google.registry.flows.ResourceFlowUtils.createResolvedTransferData;
import static google.registry.flows.ResourceFlowUtils.handlePendingTransferOnDelete;
import static google.registry.flows.ResourceFlowUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE_FAILURE;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE_FAILURE;
import static google.registry.util.FormattingLogger.getLoggerForCallerClass;
import static google.registry.util.PipelineUtils.createJobPath;
import static java.math.RoundingMode.CEILING;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.appengine.api.taskqueue.LeaseOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Multiset;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.dns.DnsQueue;
import google.registry.flows.async.DeleteContactsAndHostsAction.DeletionResult.Type;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.mapreduce.inputs.NullInput;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferStatus;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;
import google.registry.util.Retrier;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;

/**
 * A mapreduce that processes batch asynchronous deletions of contact and host resources by mapping
 * over all domains and domain applications and checking for any references to the contacts/hosts in
 * pending deletion.
 */
@Action(path = "/_dr/task/deleteContactsAndHosts")
public class DeleteContactsAndHostsAction implements Runnable {

  /** The HTTP parameter name used to specify the websafe key of the resource to delete. */
  public static final String PARAM_RESOURCE_KEY = "resourceKey";
  public static final String PARAM_REQUESTING_CLIENT_ID = "requestingClientId";
  public static final String PARAM_IS_SUPERUSER = "isSuperuser";
  public static final String QUEUE_ASYNC_DELETE = "async-delete-pull";

  static final String KIND_CONTACT = getKind(ContactResource.class);
  static final String KIND_HOST = getKind(HostResource.class);

  private static final long LEASE_MINUTES = 20;
  private static final FormattingLogger logger = getLoggerForCallerClass();
  private static final int MAX_REDUCE_SHARDS = 50;
  private static final int DELETES_PER_SHARD = 5;

  @Inject Clock clock;
  @Inject MapreduceRunner mrRunner;
  @Inject @Named(QUEUE_ASYNC_DELETE) Queue queue;
  @Inject Response response;
  @Inject Retrier retrier;
  @Inject DeleteContactsAndHostsAction() {}

  @Override
  public void run() {
    LeaseOptions options =
        LeaseOptions.Builder.withCountLimit(maxLeaseCount()).leasePeriod(LEASE_MINUTES, MINUTES);
    List<TaskHandle> tasks = queue.leaseTasks(options);
    if (tasks.isEmpty()) {
      response.setPayload("No contact/host deletion tasks in pull queue.");
      return;
    }
    Multiset<String> kindCounts = HashMultiset.create(2);
    ImmutableList.Builder<DeletionRequest> builder = new ImmutableList.Builder<>();
    ImmutableList.Builder<Key<? extends EppResource>> resourceKeys = new ImmutableList.Builder<>();
    final List<TaskHandle> tasksToDelete = new ArrayList<>();
    for (TaskHandle task : tasks) {
      try {
        Optional<DeletionRequest> deletionRequest =
            DeletionRequest.createFromTask(task, clock.nowUtc());
        if (deletionRequest.isPresent()) {
          builder.add(deletionRequest.get());
          resourceKeys.add(deletionRequest.get().key());
          kindCounts.add(deletionRequest.get().key().getKind());
        } else {
          tasksToDelete.add(task);
        }
      } catch (Exception e) {
        logger.severefmt(
            e, "Could not parse async deletion request, delaying task for a day: %s", task);
        // Grab the lease for a whole day, so that it won't continue throwing errors every five
        // minutes.
        queue.modifyTaskLease(task, 1L, DAYS);
      }
    }
    deleteTasksWithRetry(tasksToDelete);
    ImmutableList<DeletionRequest> deletionRequests = builder.build();
    if (deletionRequests.isEmpty()) {
      logger.info("No asynchronous deletions to process because all were already handled.");
      response.setPayload("All requested deletions of contacts/hosts have already occurred.");
    } else {
      logger.infofmt(
          "Processing asynchronous deletion of %d contacts and %d hosts: %s",
          kindCounts.count(KIND_CONTACT), kindCounts.count(KIND_HOST), resourceKeys.build());
      runMapreduce(deletionRequests);
    }
  }

  /** Deletes a list of tasks from the async delete queue using a retrier. */
  private void deleteTasksWithRetry(final List<TaskHandle> tasks) {
    if (tasks.isEmpty()) {
      return;
    }
    retrier.callWithRetry(
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            queue.deleteTask(tasks);
            return null;
          }}, TransientFailureException.class);
  }

  private void runMapreduce(ImmutableList<DeletionRequest> deletionRequests) {
    try {
      int numReducers =
          Math.min(MAX_REDUCE_SHARDS, divide(deletionRequests.size(), DELETES_PER_SHARD, CEILING));
      response.sendJavaScriptRedirect(createJobPath(mrRunner
          .setJobName("Check for EPP resource references and then delete")
          .setModuleName("backend")
          .setDefaultReduceShards(numReducers)
          .runMapreduce(
              new DeleteContactsAndHostsMapper(deletionRequests),
              new DeleteEppResourceReducer(),
              ImmutableList.of(
                  // Add an extra shard that maps over a null domain. See the mapper code for why.
                  new NullInput<DomainBase>(),
                  EppResourceInputs.createEntityInput(DomainBase.class)))));
    } catch (Throwable t) {
      logger.severefmt(t, "Error while kicking off mapreduce to delete contacts/hosts");
    }
  }

  /**
   * A mapper that iterates over all {@link DomainBase} entities.
   *
   * <p>It emits the target key and {@code true} for domains referencing the target resource. For
   * the special input of {@code null} it emits the target key and {@code false}.
   */
  public static class DeleteContactsAndHostsMapper
      extends Mapper<DomainBase, DeletionRequest, Boolean> {

    private static final long serialVersionUID = -253652818502690537L;

    private final ImmutableList<DeletionRequest> deletionRequests;

    DeleteContactsAndHostsMapper(ImmutableList<DeletionRequest> resourcesToDelete) {
      this.deletionRequests = resourcesToDelete;
    }

    @Override
    public void map(DomainBase domain) {
      for (DeletionRequest deletionRequest : deletionRequests) {
        if (domain == null) {
          // The reducer only runs if at least one value is emitted. We add a null input to the
          // mapreduce and emit one 'false' for each deletion request so that the reducer always
          // runs for each requested deletion (so that it can finish up tasks if nothing else).
          emit(deletionRequest, false);
        } else if (isActive(domain, deletionRequest.lastUpdateTime())
            && isLinked(domain, deletionRequest.key())) {
          emit(deletionRequest, true);
          getContext()
              .incrementCounter(
                  String.format("active Domain-%s links found", deletionRequest.key().getKind()));
        }
      }
      if (domain != null) {
        getContext().incrementCounter("domains processed");
      }
    }

    /** Determine whether the target resource is a linked resource on the domain. */
    private boolean isLinked(DomainBase domain, Key<? extends EppResource> resourceKey) {
      if (resourceKey.getKind().equals(KIND_CONTACT)) {
        return domain.getReferencedContacts().contains(resourceKey);
      } else if (resourceKey.getKind().equals(KIND_HOST)) {
        return domain.getNameservers().contains(resourceKey);
      } else {
        throw new IllegalStateException("EPP resource key of unknown type: " + resourceKey);
      }
    }
  }

  /**
   * A reducer that checks if the EPP resource to be deleted is referenced anywhere, and then
   * deletes it if not and unmarks it for deletion if so.
   */
  public static class DeleteEppResourceReducer
      extends Reducer<DeletionRequest, Boolean, Void> {

    private static final long serialVersionUID = 6569363449285506326L;
    private static final DnsQueue dnsQueue = DnsQueue.create();

    @Override
    public void reduce(final DeletionRequest deletionRequest, ReducerInput<Boolean> values) {
      final boolean hasNoActiveReferences = !Iterators.contains(values, true);
      logger.infofmt("Processing async deletion request for %s", deletionRequest.key());
      DeletionResult result = ofy().transactNew(new Work<DeletionResult>() {
        @Override
        @SuppressWarnings("unchecked")
        public DeletionResult run() {
          DeletionResult deletionResult =
              attemptToDeleteResource(deletionRequest, hasNoActiveReferences);
          getQueue(QUEUE_ASYNC_DELETE).deleteTask(deletionRequest.task());
          return deletionResult;
        }});
      String resourceNamePlural = deletionRequest.key().getKind() + "s";
      getContext().incrementCounter(result.type().renderCounterText(resourceNamePlural));
      logger.infofmt(
          "Result of async deletion for resource %s: %s",
          deletionRequest.key(), result.pollMessageText());
    }

    private DeletionResult attemptToDeleteResource(
        DeletionRequest deletionRequest, boolean hasNoActiveReferences) {
      DateTime now = ofy().getTransactionTime();
      EppResource resource =
          ofy().load().key(deletionRequest.key()).now().cloneProjectedAtTime(now);
      // Double-check transactionally that the resource is still active and in PENDING_DELETE.
      if (!doesResourceStateAllowDeletion(resource, now)) {
        return DeletionResult.create(Type.ERRORED, "");
      }

      boolean requestedByCurrentOwner =
          resource.getCurrentSponsorClientId().equals(deletionRequest.requestingClientId());
      boolean deleteAllowed =
          hasNoActiveReferences && (requestedByCurrentOwner || deletionRequest.isSuperuser());

      String resourceTypeName =
          resource.getClass().getAnnotation(ExternalMessagingName.class).value();
      String pollMessageText =
          deleteAllowed
              ? String.format("Deleted %s %s.", resourceTypeName, resource.getForeignKey())
              : String.format(
                  "Can't delete %s %s because %s.",
                  resourceTypeName,
                  resource.getForeignKey(),
                  requestedByCurrentOwner
                      ? "it is referenced by a domain"
                      : "it was transferred prior to deletion");

      HistoryEntry historyEntry =
          new HistoryEntry.Builder()
              .setClientId(deletionRequest.requestingClientId())
              .setModificationTime(now)
              .setType(getHistoryEntryType(resource, deleteAllowed))
              .setParent(deletionRequest.key())
              .build();

      PollMessage.OneTime pollMessage =
          new PollMessage.OneTime.Builder()
              .setClientId(deletionRequest.requestingClientId())
              .setMsg(pollMessageText)
              .setParent(historyEntry)
              .setEventTime(now)
              .build();

      EppResource resourceToSave;
      if (deleteAllowed) {
        EppResource.Builder<?, ?> resourceToSaveBuilder;
        if (resource instanceof ContactResource) {
          ContactResource contact = (ContactResource) resource;
          resourceToSaveBuilder = contact.asBuilder()
              .setTransferData(createResolvedTransferData(
                  contact.getTransferData(), TransferStatus.SERVER_CANCELLED, now))
              .wipeOut();
        } else {
          resourceToSaveBuilder = resource.asBuilder();
        }
        resourceToSave = resourceToSaveBuilder
            .setDeletionTime(now)
            .setStatusValues(null)
            .build();
        performDeleteTasks(resource, resourceToSave, now, historyEntry);
        updateForeignKeyIndexDeletionTime(resourceToSave);
      } else {
        resourceToSave = resource.asBuilder().removeStatusValue(PENDING_DELETE).build();
      }
      ofy().save().<ImmutableObject>entities(resourceToSave, historyEntry, pollMessage);
      return DeletionResult.create(
          deleteAllowed ? Type.DELETED : Type.NOT_DELETED, pollMessageText);
    }

    /**
     * Determine the proper history entry type for the delete operation, as a function of
     * whether or not the delete was successful.
     */
    private HistoryEntry.Type getHistoryEntryType(EppResource resource, boolean successfulDelete) {
      if (resource instanceof ContactResource) {
        return successfulDelete ? CONTACT_DELETE : CONTACT_DELETE_FAILURE;
      } else if (resource instanceof HostResource) {
        return successfulDelete ? HOST_DELETE : HOST_DELETE_FAILURE;
      } else {
        throw new IllegalStateException("EPP resource of unknown type: " + Key.create(resource));
      }
    }

    /** Perform any type-specific tasks on the resource to be deleted (and/or its dependencies). */
    private void performDeleteTasks(
        EppResource existingResource,
        EppResource deletedResource,
        DateTime deletionTime,
        HistoryEntry historyEntryForDelete) {
      if (existingResource instanceof ContactResource) {
        handlePendingTransferOnDelete(
            (ContactResource) existingResource,
            (ContactResource) deletedResource,
            deletionTime,
            historyEntryForDelete);
      } else if (existingResource instanceof HostResource) {
        HostResource host = (HostResource) existingResource;
        if (host.getSuperordinateDomain() != null) {
          dnsQueue.addHostRefreshTask(host.getFullyQualifiedHostName());
          ofy().save().entity(
              ofy().load().key(host.getSuperordinateDomain()).now().asBuilder()
                  .removeSubordinateHost(host.getFullyQualifiedHostName())
                  .build());
        }
      } else {
        throw new IllegalStateException(
            "EPP resource of unknown type: " + Key.create(existingResource));
      }
    }
  }

  /** A class that encapsulates the values of a request to delete a contact or host. */
  @AutoValue
  abstract static class DeletionRequest implements Serializable {

    private static final long serialVersionUID = 5782119100274089088L;

    abstract Key<? extends EppResource> key();
    abstract DateTime lastUpdateTime();
    /**
     * The client id of the registrar that requested this deletion (which might NOT be the same as
     * the actual current owner of the resource).
     */
    abstract String requestingClientId();
    abstract boolean isSuperuser();
    abstract TaskHandle task();

    static Optional<DeletionRequest> createFromTask(TaskHandle task, DateTime now)
        throws Exception {
      ImmutableMap<String, String> params = ImmutableMap.copyOf(task.extractParams());
      Key<EppResource> resourceKey =
          Key.create(
              checkNotNull(params.get(PARAM_RESOURCE_KEY), "Resource to delete not specified"));
      EppResource resource =
          checkNotNull(ofy().load().key(resourceKey).now(), "Resource to delete doesn't exist");
      checkState(
          resource instanceof ContactResource || resource instanceof HostResource,
          "Cannot delete a %s via this action",
          resource.getClass().getSimpleName());
      if (!doesResourceStateAllowDeletion(resource, now)) {
        return Optional.absent();
      }
      return Optional.<DeletionRequest>of(
          new AutoValue_DeleteContactsAndHostsAction_DeletionRequest(
              resourceKey,
              resource.getUpdateAutoTimestamp().getTimestamp(),
              checkNotNull(
                  params.get(PARAM_REQUESTING_CLIENT_ID), "Requesting client id not specified"),
              Boolean.valueOf(
                  checkNotNull(params.get(PARAM_IS_SUPERUSER), "Is superuser not specified")),
              task));
    }
  }

  /** A class that encapsulates the values resulting from attempted contact/host deletion. */
  @AutoValue
  abstract static class DeletionResult {

    enum Type {
      DELETED("%s deleted"),
      NOT_DELETED("%s not deleted"),
      ERRORED("%s errored out during deletion");

      private final String counterFormat;

      private Type(String counterFormat) {
        this.counterFormat = counterFormat;
      }

      String renderCounterText(String resourceName) {
        return String.format(counterFormat, resourceName);
      }
    }

    abstract Type type();
    abstract String pollMessageText();

    static DeletionResult create(Type type, String pollMessageText) {
      return new AutoValue_DeleteContactsAndHostsAction_DeletionResult(type, pollMessageText);
    }
  }

  static boolean doesResourceStateAllowDeletion(EppResource resource, DateTime now) {
    Key<EppResource> key = Key.create(resource);
    if (isDeleted(resource, now)) {
      logger.warningfmt("Cannot asynchronously delete %s because it is already deleted", key);
      return false;
    }
    if (!resource.getStatusValues().contains(PENDING_DELETE)) {
      logger.warningfmt("Cannot asynchronously delete %s because it is not in PENDING_DELETE", key);
      return false;
    }
    return true;
  }
}
