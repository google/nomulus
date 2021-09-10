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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKeys;
import static google.registry.testing.DatabaseHelper.loadByKeysIfPresent;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AckPollMessagesCommand}. */
@DualDatabaseTest
public class AckPollMessagesCommandTest extends CommandTestCase<AckPollMessagesCommand> {

  private FakeClock clock = new FakeClock(DateTime.parse("2015-02-04T08:16:32.064Z"));

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private DomainHistory domainHistory;

  @BeforeEach
  final void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    command.clock = clock;
    createTld("tld");
    DomainBase domain = newDomainBase("example.tld").asBuilder().setRepoId("FSDGS-TLD").build();
    persistResource(domain);
    domainHistory =
        persistResource(
            new DomainHistory.Builder()
                .setModificationTime(clock.nowUtc())
                .setDomainRepoId(domain.getRepoId())
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setId(2406L)
                .build());
    clock.advanceOneMilli();
  }

  @TestOfyAndSql
  void testSuccess_doesntDeletePollMessagesInFuture() throws Exception {
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
    assertThat(loadByKeysIfPresent(ImmutableList.of(pm1, pm2, pm3, pm4)).values())
        .containsExactly(futurePollMessage);
    assertInStdout(
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar",
        "1-FSDGS-TLD-2406-791-2015,2015-01-08T22:33:44.000Z,ginger");
    assertNotInStdout("1-FSDGS-TLD-2406-123-2015,2015-09-01T22:33:44.000Z,notme");
  }

  @TestOfyAndSql
  void testSuccess_resavesAutorenewPollMessages() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar").createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives").createVKey();
    Autorenew autorenew =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setId(625L)
                .setParentKey(domainHistory.createVKey().getOfyKey())
                .setEventTime(DateTime.parse("2011-04-15T22:33:44Z"))
                .setRegistrarId("TheRegistrar")
                .setMsg("autorenew")
                .build());
    Autorenew resaved =
        autorenew.asBuilder().setEventTime(DateTime.parse("2012-04-15T22:33:44Z")).build();
    VKey<Autorenew> pm3 = autorenew.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(loadByKeysIfPresent(ImmutableList.of(pm1, pm2, pm3)).values())
        .containsExactly(resaved);
    assertInStdout(
        "1-FSDGS-TLD-2406-625-2011,2011-04-15T22:33:44.000Z,autorenew",
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar");
  }

  @TestOfyAndSql
  void testSuccess_deletesExpiredAutorenewPollMessages() throws Exception {
    VKey<OneTime> pm1 =
        persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar").createVKey();
    VKey<OneTime> pm2 =
        persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives").createVKey();
    Autorenew autorenew =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setId(625L)
                .setParentKey(domainHistory.createVKey().getOfyKey())
                .setEventTime(DateTime.parse("2011-04-15T22:33:44Z"))
                .setAutorenewEndTime(DateTime.parse("2012-01-01T22:33:44Z"))
                .setRegistrarId("TheRegistrar")
                .setMsg("autorenew")
                .build());
    VKey<Autorenew> pm3 = autorenew.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(loadByKeysIfPresent(ImmutableList.of(pm1, pm2, pm3))).isEmpty();
    assertInStdout(
        "1-FSDGS-TLD-2406-625-2011,2011-04-15T22:33:44.000Z,autorenew",
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar");
  }

  @TestOfyAndSql
  void testSuccess_onlyDeletesPollMessagesMatchingMessage() throws Exception {
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
    assertThat(loadByKeysIfPresent(ImmutableList.of(pm1, pm2, pm3, pm4)).values())
        .containsExactly(notMatched1, notMatched2);
  }

  @TestOfyAndSql
  void testSuccess_onlyDeletesPollMessagesMatchingClientId() throws Exception {
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
                .setParentKey(domainHistory.createVKey().getOfyKey())
                .setRegistrarId("NewRegistrar")
                .setEventTime(DateTime.parse("2013-06-01T22:33:44Z"))
                .setMsg("baaaahh")
                .build());
    VKey<OneTime> pm3 = notMatched.createVKey();
    runCommand("-c", "TheRegistrar");
    assertThat(loadByKeysIfPresent(ImmutableList.of(pm1, pm2, pm3)).values())
        .containsExactly(notMatched);
  }

  @TestOfyAndSql
  void testSuccess_dryRunDoesntDeleteAnything() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives");
    OneTime pm3 = persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "ginger");
    OneTime pm4 = persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "notme");
    runCommand("-c", "TheRegistrar", "-d");
    assertThat(
            loadByKeys(
                ImmutableList.of(pm1, pm2, pm3, pm4).stream()
                    .map(OneTime::createVKey)
                    .collect(toImmutableList())))
        .containsExactly(pm1, pm2, pm3, pm4);
  }

  private OneTime persistPollMessage(long id, DateTime eventTime, String message) {
    return persistResource(
        new PollMessage.OneTime.Builder()
            .setId(id)
            .setParentKey(
                Key.create(
                    Key.create(DomainBase.class, "FSDGS-TLD"),
                    HistoryEntry.class,
                    domainHistory.getId()))
            .setRegistrarId("TheRegistrar")
            .setEventTime(eventTime)
            .setMsg(message)
            .build());
  }
}
