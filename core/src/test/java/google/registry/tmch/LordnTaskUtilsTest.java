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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainAndEnqueueLordn;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.domain.DomainBase;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar.Type;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import google.registry.util.Clock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link LordnTaskUtils}. */
@DualDatabaseTest
public class LordnTaskUtilsTest {

  private static final Clock clock = new FakeClock(DateTime.parse("2010-05-01T10:11:12Z"));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(clock)
          .withTaskQueue()
          .build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    createTld("example");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private DomainBase.Builder newDomainBuilder() {
    return new DomainBase.Builder()
        .setDomainName("fleece.example")
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .setCreationClientId("TheRegistrar")
        .setRegistrant(persistActiveContact("jd1234").createVKey())
        .setSmdId("smdzzzz")
        .setCreationClientId("TheRegistrar");
  }

  @TestOfyAndSql
  void test_enqueueDomainBaseTask_sunrise() {
    persistDomainAndEnqueueLordn(newDomainBuilder().setRepoId("A-EXAMPLE").build());
    String expectedPayload =
        "A-EXAMPLE,fleece.example,smdzzzz,1,2010-05-01T10:11:12.000Z";
    assertTasksEnqueued(
        "lordn-sunrise", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @TestOfyAndSql
  void test_enqueueDomainBaseTask_claims() {
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
    assertTasksEnqueued("lordn-claims", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @TestOfyAndSql
  void test_oteRegistrarWithNullIanaId() {
    tm().transact(
            () ->
                tm().put(
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

  @TestOfyOnly // moot in SQL since the domain creation fails the registrar foreign key check
  void test_enqueueDomainBaseTask_throwsExceptionOnInvalidRegistrar() {
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

  @TestOfyAndSql
  void test_enqueueDomainBaseTask_throwsNpeOnNullDomain() {
    assertThrows(
        NullPointerException.class,
        () -> tm().transactNew(() -> LordnTaskUtils.enqueueDomainBaseTask(null)));
  }
}
