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

package google.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.Action.Method.POST;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryEnvironment;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * Deletes all {@link EppResource} objects in Datastore, including indices and descendants.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do. That makes this command hard to use, which is appropriate, given
 * the drastic consequences of accidental execution.
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/killAllEppResources",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class KillAllEppResourcesAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject KillAllEppResourcesAction() {}

  @Override
  public void run() {
    checkArgument(
        RegistryEnvironment.get() == RegistryEnvironment.CRASH
            || RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "DO NOT RUN ANYWHERE ELSE EXCEPT CRASH OR TESTS.");
    mrRunner
        .setJobName("Delete all EppResources, children, and indices")
        .setModuleName("tools")
        .runMapreduce(
            new KillAllEppResourcesMapper(),
            new KillAllEntitiesReducer(),
            ImmutableList.of(EppResourceInputs.createIndexInput()))
        .sendLinkToMapreduceConsole(response);
  }

  static class KillAllEppResourcesMapper extends Mapper<EppResourceIndex, Key<?>, Key<?>> {

    private static final long serialVersionUID = 8205309000002507407L;

    /**
     * Delete an {@link EppResourceIndex}, its referent, all descendants of each referent, and the
     * {@link ForeignKeyIndex} of the referent, as appropriate.
     *
     * <p>This will delete:
     * <ul>
     *   <li>All {@link ForeignKeyIndex} types
     *   <li>{@link EppResourceIndex}
     *   <li>All {@link EppResource} types
     *   <li>{@code HistoryEntry}
     *   <li>All {@code BillingEvent} types
     *   <li>All {@code PollMessage} types
     * </ul>
     */
    @Override
    public void map(final EppResourceIndex eri) {
      Key<EppResourceIndex> eriKey = Key.create(eri);
      emitAndIncrementCounter(eriKey, eriKey);
      Key<?> resourceKey = eri.getKey();
      for (Key<Object> key : ofy().load().ancestor(resourceKey).keys()) {
        emitAndIncrementCounter(resourceKey, key);
      }
      EppResource resource = ofy().load().key(eri.getKey()).now();
      // TODO(b/28247733): What about FKI's for renamed hosts?
      Key<?> indexKey = ForeignKeyIndex.createKey(resource);
      emitAndIncrementCounter(indexKey, indexKey);
    }

    private void emitAndIncrementCounter(Key<?> ancestor, Key<?> child) {
      emit(ancestor, child);
      getContext().incrementCounter("entities emitted");
      getContext().incrementCounter(String.format("%s emitted", child.getKind()));
    }
  }
}
