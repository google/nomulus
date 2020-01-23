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

package google.registry.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistDomainAndEnqueueLordn;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.Assert.assertThrows;

import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar.Type;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Clock;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LordnTaskUtils}. */
@RunWith(JUnit4.class)
public class LordnTaskUtilsTest {

  private static final Clock clock = new FakeClock(DateTime.parse("2010-05-01T10:11:12Z"));

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();
  @Rule
  public final InjectRule inject = new InjectRule();

  @Before
  public void before() {
    createTld("example");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private DomainBase.Builder newDomainBuilder() {
    return new DomainBase.Builder()
        .setFullyQualifiedDomainName("fleece.example")
        .setRegistrant(Key.create(persistActiveContact("jd1234")))
        .setSmdId("smdzzzz")
        .setCreationClientId("TheRegistrar");
  }

  @Test
  public void test_enqueueDomainBaseTask_sunrise() {
    persistDomainAndEnqueueLordn(newDomainBuilder().setRepoId("A-EXAMPLE").build());
    String expectedPayload =
        "A-EXAMPLE,fleece.example,smdzzzz,1,2010-05-01T10:11:12.000Z";
    assertTasksEnqueued(
        "lordn-sunrise", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @Test
  public void test_enqueueDomainBaseTask_claims() {
    DomainBase domain =
        newDomainBuilder()
            .setRepoId("11-EXAMPLE")
            .setLaunchNotice(
                LaunchNotice.create(
                    "landrush1tcn", null, null, DateTime.parse("2010-05-01T09:11:12Z")))
            .build();
    persistDomainAndEnqueueLordn(domain);
    String expectedPayload = "11-EXAMPLE,fleece.example,landrush1tcn,1,2010-05-01T10:11:12.000Z,"
        + "2010-05-01T09:11:12.000Z";
    assertTasksEnqueued(
        "lordn-claims", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @Test
  public void test_oteRegistrarWithNullIanaId() {
    tm()
        .transact(
            () ->
                ofy()
                    .save()
                    .entity(
                        loadRegistrar("TheRegistrar")
                            .asBuilder()
                            .setType(Type.OTE)
                            .setIanaIdentifier(null)
                            .build()));
    persistDomainAndEnqueueLordn(newDomainBuilder().setRepoId("3-EXAMPLE").build());
    String expectedPayload = "3-EXAMPLE,fleece.example,smdzzzz,null,2010-05-01T10:11:12.000Z";
    assertTasksEnqueued(
        "lordn-sunrise", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @Test
  public void test_enqueueDomainBaseTask_throwsExceptionOnInvalidRegistrar() {
    DomainBase domain =
        newDomainBuilder()
            .setRepoId("9000-EXAMPLE")
            .setCreationClientId("nonexistentRegistrar")
            .build();
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> persistDomainAndEnqueueLordn(domain));
    assertThat(thrown)
        .hasMessageThat()
        .contains("No registrar found for client id: nonexistentRegistrar");
  }

  @Test
  public void test_enqueueDomainBaseTask_throwsNpeOnNullDomain() {
    assertThrows(
        NullPointerException.class,
        () -> tm().transactNew(() -> LordnTaskUtils.enqueueDomainBaseTask(null)));
  }
}
