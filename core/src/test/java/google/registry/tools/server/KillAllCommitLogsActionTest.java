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

import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResourceWithCommitLog;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.util.Arrays.asList;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Tests for {@link KillAllCommitLogsAction}. */
class KillAllCommitLogsActionTest extends MapreduceTestCase<KillAllCommitLogsAction> {

  private static final ImmutableList<Class<? extends ImmutableObject>> AFFECTED_TYPES =
      ImmutableList.of(
          CommitLogBucket.class,
          CommitLogCheckpoint.class,
          CommitLogCheckpointRoot.class,
          CommitLogMutation.class,
          CommitLogManifest.class);

  private void runMapreduce() throws Exception {
    action = new KillAllCommitLogsAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  void testKill() throws Exception {
    int nextContactId = 5432;
    for (String tld : asList("tld1", "tld2")) {
      createTld(tld);
      persistResourceWithCommitLog(
          newContactResource(String.format("abc%d", nextContactId++)));
    }
    persistResource(CommitLogCheckpointRoot.create(START_OF_TIME.plusDays(1)));
    DateTime bucketTime = START_OF_TIME.plusDays(2);
    persistResource(
        CommitLogCheckpoint.create(
            START_OF_TIME.plusDays(1),
            ImmutableMap.of(1, bucketTime, 2, bucketTime, 3, bucketTime)));
    for (Class<?> clazz : AFFECTED_TYPES) {
      assertWithMessage("entities of type " + clazz).that(ofy().load().type(clazz)).isNotEmpty();
    }
    ImmutableList<?> otherStuff =
        Streams.stream(ofy().load())
            .filter(obj -> !AFFECTED_TYPES.contains(obj.getClass()))
            .collect(toImmutableList());
    assertThat(otherStuff).isNotEmpty();
    runMapreduce();
    for (Class<?> clazz : AFFECTED_TYPES) {
      assertWithMessage("entities of type " + clazz).that(ofy().load().type(clazz)).isEmpty();
    }
    // Filter out raw Entity objects created by the mapreduce.
    assertThat(filter(ofy().load(), not(instanceOf(Entity.class))))
        .containsExactlyElementsIn(otherStuff);
  }
}
