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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.request.Action.Method.POST;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.inputs.InMemoryInput;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryEnvironment;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.stream.Stream;
import javax.inject.Inject;

/**
 * Deletes all commit logs in Datastore.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do. That makes this command hard to use, which is appropriate, given
 * the drastic consequences of accidental execution.
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/killAllCommitLogs",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class KillAllCommitLogsAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject KillAllCommitLogsAction() {}

  @Override
  public void run() {
    checkArgument(
        RegistryEnvironment.get() == RegistryEnvironment.CRASH
            || RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "DO NOT RUN ANYWHERE ELSE EXCEPT CRASH OR TESTS.");
    // Create a in-memory input, assigning each bucket to its own shard for maximum parallelization,
    // with one extra shard for the CommitLogCheckpointRoot.
    Input<Key<?>> input =
        new InMemoryInput<>(
            Lists.partition(
                Streams.concat(
                        Stream.of(CommitLogCheckpointRoot.getKey()),
                        CommitLogBucket.getAllBucketKeys().stream())
                    .collect(toImmutableList()),
                1));
    mrRunner
        .setJobName("Delete all commit logs")
        .setModuleName("tools")
        .runMapreduce(
            new KillAllCommitLogsMapper(), new KillAllEntitiesReducer(), ImmutableList.of(input))
        .sendLinkToMapreduceConsole(response);
  }

  /**
   * Mapper to delete a {@link CommitLogBucket} or {@link CommitLogCheckpointRoot} and any commit
   * logs or checkpoints that descend from it.
   *
   * <p>This will delete:
   * <ul>
   *   <li>{@link CommitLogBucket}
   *   <li>{@code CommitLogCheckpoint}
   *   <li>{@link CommitLogCheckpointRoot}
   *   <li>{@code CommitLogManifest}
   *   <li>{@code CommitLogMutation}
   * </ul>
   */
  static class KillAllCommitLogsMapper extends Mapper<Key<?>, Key<?>, Key<?>> {

    private static final long serialVersionUID = 1504266335352952033L;

    @Override
    public void map(Key<?> bucketOrRoot) {
      for (Key<Object> key : auditedOfy().load().ancestor(bucketOrRoot).keys()) {
        emit(bucketOrRoot, key);
        getContext().incrementCounter("entities emitted");
        getContext().incrementCounter(String.format("%s emitted", key.getKind()));
     }
    }
  }
}

