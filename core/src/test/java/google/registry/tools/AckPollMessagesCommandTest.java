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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.reporting.HistoryEntry;
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
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives");
    OneTime pm3 = persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "ginger");
    OneTime pm4 = persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "notme");
    runCommand("-c", "TheRegistrar");
    assertThat(ofy().load().entities(pm1, pm2, pm3, pm4).values()).containsExactly(pm4);
    assertInStdout(
        "1-FSDGS-TLD-2406-624-2013,2013-05-01T22:33:44.000Z,ninelives",
        "1-FSDGS-TLD-2406-316-2014,2014-01-01T22:33:44.000Z,foobar",
        "1-FSDGS-TLD-2406-791-2015,2015-01-08T22:33:44.000Z,ginger");
    assertNotInStdout("1-FSDGS-TLD-2406-123-2015,2015-09-01T22:33:44.000Z,notme");
  }

  @Test
  public void testSuccess_doesntDeleteAutorenewPollMessages() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives");
    Autorenew pm3 =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setId(624L)
                .setParentKey(
                    Key.create(
                        Key.create(DomainBase.class, "AAFSGS-TLD"), HistoryEntry.class, 99406L))
                .setEventTime(DateTime.parse("2011-04-15T22:33:44Z"))
                .setClientId("TheRegistrar")
                .build());
    runCommand("-c", "TheRegistrar");
    assertThat(ofy().load().entities(pm1, pm2, pm3).values()).containsExactly(pm3);
  }

  @Test
  public void testSuccess_onlyDeletesPollMessagesMatchingMessage() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "food is good");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "theft is bad");
    OneTime pm3 = persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "mmmmmfood");
    OneTime pm4 = persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "time flies");
    runCommand("-c", "TheRegistrar", "-m", "food");
    assertThat(ofy().load().entities(pm1, pm2, pm3, pm4).values()).containsExactly(pm2, pm4);
  }

  @Test
  public void testSuccess_onlyDeletesPollMessagesMatchingClientId() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "food is good");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "theft is bad");
    OneTime pm3 =
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
    runCommand("-c", "TheRegistrar");
    assertThat(ofy().load().entities(pm1, pm2, pm3).values()).containsExactly(pm3);
  }

  @Test
  public void testSuccess_dryRunDoesntDeleteAnything() throws Exception {
    OneTime pm1 = persistPollMessage(316L, DateTime.parse("2014-01-01T22:33:44Z"), "foobar");
    OneTime pm2 = persistPollMessage(624L, DateTime.parse("2013-05-01T22:33:44Z"), "ninelives");
    OneTime pm3 = persistPollMessage(791L, DateTime.parse("2015-01-08T22:33:44Z"), "ginger");
    OneTime pm4 = persistPollMessage(123L, DateTime.parse("2015-09-01T22:33:44Z"), "notme");
    runCommand("-c", "TheRegistrar", "-d");
    assertThat(ofy().load().entities(pm1, pm2, pm3, pm4).values())
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
