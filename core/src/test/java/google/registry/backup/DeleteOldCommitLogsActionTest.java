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

package google.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableList;
import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.ofy.Ofy;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DeleteOldCommitLogsAction}. */
public class DeleteOldCommitLogsActionTest
    extends MapreduceTestCase<DeleteOldCommitLogsAction> {

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  private final FakeResponse response = new FakeResponse();
  private ContactResource contact;

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    action = new DeleteOldCommitLogsAction();
    action.mrRunner = makeDefaultRunner();
    action.response = response;
    action.clock = clock;
    action.maxAge = Duration.standardDays(30);

    ContactResource contact = DatastoreHelper.persistActiveContact("TheRegistrar");
    clock.advanceBy(Duration.standardDays(1));
    DatastoreHelper.persistResourceWithCommitLog(contact);

    prepareData();
  }

  private void runMapreduce(Duration maxAge) throws Exception {
    action.maxAge = maxAge;
    action.run();
    executeTasksUntilEmpty("mapreduce");
    ofy().clearSessionCache();
  }

  private void mutateContact(String email) {
    ofy().clearSessionCache();
    ContactResource contact = ofy().load()
        .type(ContactResource.class)
        .first()
        .now()
        .asBuilder()
        .setEmailAddress(email)
        .build();
    DatastoreHelper.persistResourceWithCommitLog(contact);
  }

  private void prepareData() {

    for (int i = 0; i < 10; i++) {
      clock.advanceBy(Duration.standardDays(7));
      String email = String.format("pumpkin_%d@cat.test", i);
      mutateContact(email);
    }
    ofy().clearSessionCache();

    contact = ofy().load().type(ContactResource.class).first().now();

    // The following value might change if {@link CommitLogRevisionsTranslatorFactory} changes.
    assertThat(contact.getRevisions().size()).isEqualTo(6);

    // Before deleting the unneeded manifests - we have 11 of them (one for the first
    // creation, and 10 more for the mutateContacts)
    assertThat(ofy().load().type(CommitLogManifest.class).count()).isEqualTo(11);
    // And each DatastoreHelper.persistResourceWithCommitLog creates 3 mutations
    assertThat(ofy().load().type(CommitLogMutation.class).count()).isEqualTo(33);
  }

  private <T> ImmutableList<T> ofyLoadType(Class<T> clazz) {
    return ImmutableList.copyOf(ofy().load().type(clazz).iterable());
  }

  /** Check that with very short maxAge, only the referenced elements remain. */
  @Test
  void test_shortMaxAge() throws Exception {
    runMapreduce(Duration.millis(1));

    assertThat(ImmutableList.copyOf(ofy().load().type(CommitLogManifest.class).keys().iterable()))
        .containsExactlyElementsIn(contact.getRevisions().values());

    // And each DatastoreHelper.persistResourceWithCommitLog creates 3 mutations
    assertThat(ofyLoadType(CommitLogMutation.class)).hasSize(contact.getRevisions().size() * 3);
  }

  /** Check that with very long maxAge, all the elements remain. */
  @Test
  void test_longMaxAge() throws Exception {

    ImmutableList<CommitLogManifest> initialManifests = ofyLoadType(CommitLogManifest.class);
    ImmutableList<CommitLogMutation> initialMutations = ofyLoadType(CommitLogMutation.class);

    runMapreduce(Duration.standardDays(1000));

    assertThat(ofyLoadType(CommitLogManifest.class)).containsExactlyElementsIn(initialManifests);
    assertThat(ofyLoadType(CommitLogMutation.class)).containsExactlyElementsIn(initialMutations);
  }
}
