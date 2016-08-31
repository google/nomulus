// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.flows.ResourceFlowUtils.prepareDeletedResourceAsBuilder;
import static google.registry.flows.ResourceFlowUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.PipelineUtils.createJobPath;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;
import com.googlecode.objectify.Work;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.mapreduce.inputs.NullInput;
import google.registry.model.EppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;
import google.registry.util.TypeUtils.TypeInstantiator;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * A mapreduce to delete the specified EPP resource, but ONLY if it is not referred to by any
 * existing DomainBase entity.
 */
public abstract class DeleteEppResourceAction<T extends EppResource> implements Runnable {

  /** The HTTP parameter name used to specify the websafe key of the resource to delete. */
  public static final String PARAM_RESOURCE_KEY = "resourceKey";
  public static final String PARAM_REQUESTING_CLIENT_ID = "requestingClientId";
  public static final String PARAM_IS_SUPERUSER = "superuser";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  @Inject @Parameter(PARAM_RESOURCE_KEY) String resourceKeyString;
  @Inject @Parameter(PARAM_REQUESTING_CLIENT_ID) String requestingClientId;
  @Inject @Parameter(PARAM_IS_SUPERUSER) boolean isSuperuser;
  @Inject Clock clock;
  @Inject MapreduceRunner mrRunner;
  @Inject Response response;

  DeleteEppResourceMapper<T> mapper;
  DeleteEppResourceReducer<T> reducer;

  protected DeleteEppResourceAction(
      DeleteEppResourceMapper<T> mapper,
      DeleteEppResourceReducer<T> reducer) {
    this.mapper = mapper;
    this.reducer = reducer;
  }

  @Override
  public void run() {
    Key<T> resourceKey = null;
    T resource;
    try {
      resourceKey = Key.create(resourceKeyString);
      resource = checkArgumentNotNull(ofy().load().key(resourceKey).now());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(resourceKey == null
          ? "Could not parse key string: " + resourceKeyString
          : "Could not load resource for key: " + resourceKey);
    }
    checkArgument(
        resource.getClass().equals(new TypeInstantiator<T>(getClass()){}.getExactType()),
        String.format("Cannot delete a %s via this action.", resource.getClass().getSimpleName()));
    checkState(
        !isDeleted(resource, clock.nowUtc()),
        "Resource %s is already deleted.", resource.getForeignKey());
    checkState(
        resource.getStatusValues().contains(StatusValue.PENDING_DELETE),
        "Resource %s is not set as PENDING_DELETE", resource.getForeignKey());
    mapper.setTargetResource(resourceKey);
    reducer.setClient(requestingClientId, isSuperuser);
    logger.infofmt("Executing Delete EPP resource mapreduce for %s", resourceKey);
    try {
      response.sendJavaScriptRedirect(createJobPath(mrRunner
          .setJobName("Check for EPP resource references and then delete")
          .setModuleName("backend")
          .runMapreduce(
              mapper,
              reducer,
              ImmutableList.of(
                  // Add an extra shard that maps over a null domain. See the mapper code for why.
                  new NullInput<DomainBase>(),
                  EppResourceInputs.createEntityInput(DomainBase.class)))));
    } catch (Throwable t) {
      logger.severefmt(
          t, "Error while kicking off DeleteEppResource MR for %s", resource.getForeignKey());
    }
  }

  /**
   * A mapper that iterates over all {@link DomainBase} entities.
   *
   * <p>It emits the target key and {@code true} for domains referencing the target resource. For
   * the special input of {@code null} it emits the target key and {@code false}.
   */
  public abstract static class DeleteEppResourceMapper<T extends EppResource>
      extends Mapper<DomainBase, Key<T>, Boolean> {

    private static final long serialVersionUID = -7355145176854995813L;

    private DateTime targetResourceUpdateTimestamp;
    private Key<T> targetEppResourceKey;

    private void setTargetResource(Key<T> targetEppResourceKey)  {
      this.targetEppResourceKey = targetEppResourceKey;
      this.targetResourceUpdateTimestamp =
          ofy().load().key(targetEppResourceKey).now().getUpdateAutoTimestamp().getTimestamp();
    }

    /** Determine whether the target resource is a linked resource on the domain. */
    protected abstract boolean isLinked(DomainBase domain, Ref<T> targetResourceRef);

    @Override
    public void map(DomainBase domain) {
      // The reducer only runs if at least one value is emitted. We add a null input to the
      // mapreduce and always emit 'false' for it to force the reducer to run. We can then emit
      // 'true' for linked domains and not emit anything for unlinked domains, which speeds up the
      // reducer since it will only receive true keys, of which there will be few (usually none).
      if (domain == null) {
        emit(targetEppResourceKey, false);
        return;
      }
      // The Ref can't be a field on the Mapper, because when a Ref<?> is serialized (required for
      // each MapShardTask), it uses the DeadRef version, which contains the Ref's value, which
      // isn't serializable. Thankfully, this isn't expensive.
      // See: https://github.com/objectify/objectify/blob/master/src/main/java/com/googlecode/objectify/impl/ref/DeadRef.java
      if (isActive(domain, targetResourceUpdateTimestamp)
          && isLinked(domain, Ref.create(targetEppResourceKey))) {
        emit(targetEppResourceKey, true);
      }
    }
  }

  /**
   * A reducer that checks if the EPP resource to be deleted is referenced anywhere, and then
   * deletes it if not and unmarks it for deletion if so.
   */
  public abstract static class DeleteEppResourceReducer<T extends EppResource>
      extends Reducer<Key<T>, Boolean, Void> {

    private static final long serialVersionUID = 875017002097945151L;

    private String requestingClientId;
    private boolean isSuperuser;

    private void setClient(String requestingClientId, boolean isSuperuser) {
      this.requestingClientId = requestingClientId;
      this.isSuperuser = isSuperuser;
    }

    /**
     * Determine the proper history entry type for the delete operation, as a function of
     * whether or not the delete was successful.
     */
    protected abstract HistoryEntry.Type getHistoryType(boolean successfulDelete);

    /** Perform any type-specific tasks on the resource to be deleted (and/or its dependencies). */
    protected abstract void performDeleteTasks(
        T targetResource,
        T deletedResource,
        DateTime deletionTime,
        HistoryEntry historyEntryForDelete);

    @Override
    public void reduce(final Key<T> key, final ReducerInput<Boolean> values) {
      final boolean hasNoActiveReferences = !Iterators.contains(values, true);
      logger.infofmt("Processing delete request for %s", key.toString());
      String pollMessageText = ofy().transactNew(new Work<String>() {
        @Override
        @SuppressWarnings("unchecked")
        public String run() {
          DateTime now = ofy().getTransactionTime();
          T targetResource = (T) ofy().load().key(key).now().cloneProjectedAtTime(now);
          String resourceName = targetResource.getForeignKey();
          // Double-check that the resource is still active and in PENDING_DELETE within the
          // transaction.
          checkState(
              !isDeleted(targetResource, now),
              "Resource %s is already deleted.", resourceName);
          checkState(
              targetResource.getStatusValues().contains(StatusValue.PENDING_DELETE),
              "Resource %s is not in PENDING_DELETE.", resourceName);

          targetResource = (T) targetResource.asBuilder()
              .removeStatusValue(StatusValue.PENDING_DELETE)
              .build();

          boolean requestedByCurrentOwner =
              targetResource.getCurrentSponsorClientId().equals(requestingClientId);
          boolean deleteAllowed = hasNoActiveReferences && (requestedByCurrentOwner || isSuperuser);

          String resourceTypeName =
              targetResource.getClass().getAnnotation(ExternalMessagingName.class).value();
          HistoryEntry.Type historyType = getHistoryType(deleteAllowed);

          String pollMessageText = deleteAllowed
              ? String.format("Deleted %s %s.", resourceTypeName, resourceName)
              : String.format(
                  "Can't delete %s %s because %s.",
                  resourceTypeName,
                  resourceName,
                  requestedByCurrentOwner
                      ? "it is referenced by a domain"
                      : "it was transferred prior to deletion");

          HistoryEntry historyEntry = new HistoryEntry.Builder()
              .setClientId(requestingClientId)
              .setModificationTime(now)
              .setType(historyType)
              .setParent(key)
              .build();

          PollMessage.OneTime deleteResultMessage = new PollMessage.OneTime.Builder()
              .setClientId(requestingClientId)
              .setMsg(pollMessageText)
              .setParent(historyEntry)
              .setEventTime(now)
              .build();

          if (deleteAllowed) {
            T deletedResource = prepareDeletedResourceAsBuilder(targetResource, now).build();
            performDeleteTasks(targetResource, deletedResource, now, historyEntry);
            updateForeignKeyIndexDeletionTime(deletedResource);
            ofy().save().<Object>entities(deletedResource, historyEntry, deleteResultMessage);
          } else {
            ofy().save().<Object>entities(targetResource, historyEntry, deleteResultMessage);
          }
          return pollMessageText;
        }
      });
      logger.infofmt(pollMessageText);
    }
  }
}
