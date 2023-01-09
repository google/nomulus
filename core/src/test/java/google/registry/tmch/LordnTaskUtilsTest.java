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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainAndEnqueueLordn;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.domain.Domain;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.registrar.Registrar.Type;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Clock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link LordnTaskUtils}. */
public class LordnTaskUtilsTest {

  private static final Clock clock = new FakeClock(DateTime.parse("2010-05-01T10:11:12Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension final TaskQueueExtension taskQueue = new TaskQueueExtension();

  @BeforeEach
  void beforeEach() {
    createTld("example");
  }

  private static Domain.Builder newDomainBuilder() {
    return new Domain.Builder()
        .setDomainName("fleece.example")
        .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
        .setCreationRegistrarId("TheRegistrar")
        .setRegistrant(persistActiveContact("jd1234").createVKey())
        .setSmdId("smdzzzz")
        .setCreationRegistrarId("TheRegistrar");
  }

  @Test
  void test_enqueueDomainTask_sunrise() {
    persistDomainAndEnqueueLordn(newDomainBuilder().setRepoId("A-EXAMPLE").build());
    String expectedPayload =
        "A-EXAMPLE,fleece.example,smdzzzz,1,2010-05-01T10:11:12.000Z";
    assertTasksEnqueued(
        "lordn-sunrise", new TaskMatcher().payload(expectedPayload).tag("example"));
  }

  @Test
  void test_enqueueDomainTask_claims() {
    Domain domain =
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

  @Test
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

  @Test
  void test_enqueueDomainTask_throwsNpeOnNullDomain() {
    assertThrows(
        NullPointerException.class,
        () -> tm().transact(() -> LordnTaskUtils.enqueueDomainTask(null)));
  }
}
