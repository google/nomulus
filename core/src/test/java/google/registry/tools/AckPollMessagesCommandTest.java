// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link AckPollMessagesCommand}. */
public class AckPollMessagesCommandTest extends CommandTestCase<AckPollMessagesCommand> {

  private FakeClock clock = new FakeClock(DateTime.parse("2015-02-04T08:16:32.064Z"));

  @Rule public final InjectRule inject = new InjectRule();

  @Before
  public final void before() {
    inject.setStaticField(Ofy.class, "clock", clock);
    command.clock = clock;
  }

  @Test
  public void testSuccess_doesntDeletePollMessagesInFuture() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar").createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives").createVKey();
    VKey<OneTime> pm3 =
        persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "ginger").createVKey();
    OneTime futurePollMessage =
        persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "notme");
    VKey<OneTime> pm4 = futurePollMessage.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(tm().load(ImmutableList.of(pm1, pm2, pm3, pm4))).containsExactly(futurePollMessage);
    assertInStdout(
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar",
        "1-FSDGS-TLD-2406-791-2015,2015-01-08T22:33:44.000Z,ginger");
    assertNotInStdout("1-FSDGS-TLD-2406-123-2015,2015-09-01T22:33:44.000Z,notme");
  }

  @Test
  public void testSuccess_resavesAutorenewPollMessages() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar").createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives").createVKey();
    Autorenew autorenew =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setId(624L)
                .setParentKey(
                    Key.create(
                        Key.create(DomainBase.class, "AAFSGS-TLD"), HistoryEntry.class, 99406L))
                .setEventTime(DateTime.parse("2011-04-15T22:33:44Z"))
                .setClientId("TheRegistrar")
                .setMsg("autorenew")
                .build());
    Autorenew resaved =
        autorenew.asBuilder().setEventTime(DateTime.parse("2012-04-15T22:33:44Z")).build();
    VKey<Autorenew> pm3 = autorenew.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(tm().load(ImmutableList.of(pm1, pm2, pm3))).containsExactly(resaved);
    assertInStdout(
        "1-AAFSGS-TLD-99406-624-2011,2011-04-15T22:33:44.000Z,autorenew",
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar");
  }

  @Test
  public void testSuccess_deletesExpiredAutorenewPollMessages() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar").createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives").createVKey();
    Autorenew autorenew =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setId(624L)
                .setParentKey(
                    Key.create(
                        Key.create(DomainBase.class, "AAFSGS-TLD"), HistoryEntry.class, 99406L))
                .setEventTime(DateTime.parse("2011-04-15T22:33:44Z"))
                .setAutorenewEndTime(DateTime.parse("2012-01-01T22:33:44Z"))
                .setClientId("TheRegistrar")
                .setMsg("autorenew")
                .build());
    VKey<Autorenew> pm3 = autorenew.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(tm().load(ImmutableList.of(pm1, pm2, pm3))).isEmpty();
    assertInStdout(
        "1-AAFSGS-TLD-99406-624-2011,2011-04-15T22:33:44.000Z,autorenew",
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar");
  }

  @Test
  public void testSuccess_onlyDeletesPollMessagesMatchingMessage() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "food is good")
            .createVKey();
    OneTime notMatched1 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "theft is bad");
    VKey<OneTime> pm2 = notMatched1.createVKey();
    VKey<OneTime> pm3 =
        persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "mmmmmfood").createVKey();
    OneTime notMatched2 =
        persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "time flies");
    VKey<OneTime> pm4 = notMatched2.createVKey();
    runCommand("-c", "TheRegistrar", "-m", "food");
    assertThat(tm().load(ImmutableList.of(pm1, pm2, pm3, pm4)))
        .containsExactly(notMatched1, notMatched2);
  }

  @Test
  public void testSuccess_onlyDeletesPollMessagesMatchingClientId() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "food is good")
            .createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "theft is bad")
            .createVKey();
    OneTime notMatched =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setId(2474L)
                .setParentKey(
                    Key.create(
                        Key.create(DomainBase.class, "FSDGS-TLD"), HistoryEntry.class, 2406L))
                .setClientId("NewRegistrar")
                .setEventTime(DateTime.parse("2013-06-01T22:33:44Z"))
                .setMsg("baaaahh")
                .build());
    VKey<OneTime> pm3 = notMatched.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(tm().load(ImmutableList.of(pm1, pm2, pm3))).containsExactly(notMatched);
  }

  @Test
  public void testSuccess_dryRunDoesntDeleteAnything() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives");
    OneTime pm3 = persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "ginger");
    OneTime pm4 = persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "notme");
    runCommand("-c", "TheRegistrar", "-d");
    assertThat(
            tm().load(
                    ImmutableList.of(pm1, pm2, pm3, pm4).stream()
                        .map(OneTime::createVKey)
                        .collect(toImmutableList())))
        .containsExactly(pm1, pm2, pm3, pm4);
  }

  private static OneTime persistPollMessage(long id, DateTime eventTime, String message) {
    return persistResource(
        new PollMessage.OneTime.Builder()
            .setId(id)
            .setParentKey(
                Key.create(Key.create(DomainBase.class, "FSDGS-TLD"), HistoryEntry.class, 2406L))
            .setClientId("TheRegistrar")
            .setEventTime(eventTime)
            .setMsg(message)
            .build());
  }
}
