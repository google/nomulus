// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.poll;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.poll.PollMessageExternalKeyConverter.makePollMessageExternalId;
import static google.registry.model.poll.PollMessageExternalKeyConverter.parsePollMessageExternalId;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessageExternalKeyConverter.PollMessageExternalKeyParseException;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PollMessageExternalKeyConverter}. */
public class PollMessageExternalKeyConverterTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private HistoryEntry historyEntry;
  private final FakeClock clock = new FakeClock(DateTime.parse("2007-07-07T01:01:01Z"));

  @BeforeEach
  void beforeEach() {
    createTld("foobar");
    historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(persistActiveDomain("foo.foobar"))
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setPeriod(Period.create(1, Period.Unit.YEARS))
                .setXmlBytes("<xml></xml>".getBytes(UTF_8))
                .setModificationTime(clock.nowUtc())
                .setRegistrarId("TheRegistrar")
                .setTrid(Trid.create("ABC-123", "server-trid"))
                .setBySuperuser(false)
                .setReason("reason")
                .setRequestedByRegistrar(false)
                .build());
  }

  @Test
  void testSuccess_domain() {
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("5-2007");
    assertVKeysEqual(parsePollMessageExternalId("5-2007"), pollMessage.createVKey());
  }

  @Test
  void testSuccess_contact() {
    historyEntry =
        persistResource(
            DatabaseHelper.createHistoryEntryForEppResource(persistActiveContact("tim")));
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("7-2007");
    assertVKeysEqual(parsePollMessageExternalId("7-2007"), pollMessage.createVKey());
  }

  @Test
  void testSuccess_host() {
    historyEntry =
        persistResource(
            DatabaseHelper.createHistoryEntryForEppResource(persistActiveHost("time.xyz")));
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("7-2007");
    assertVKeysEqual(parsePollMessageExternalId("7-2007"), pollMessage.createVKey());
  }

  @Test
  void testFailure_tooFewComponentParts() {
    assertThrows(PollMessageExternalKeyParseException.class, () -> parsePollMessageExternalId("1"));
  }

  @Test
  void testFailure_tooManyComponentParts() {
    assertThrows(
        PollMessageExternalKeyParseException.class, () -> parsePollMessageExternalId("1-3-2009"));
  }

  @Test
  void testFailure_nonNumericIds() {
    assertThrows(
        PollMessageExternalKeyParseException.class, () -> parsePollMessageExternalId("A-2007"));
  }

  // We may have VKeys of slightly varying types, e.g. VKey<PollMessage> (superclass) and
  // VKey<PollMessage.OneTime> (subclass). We should treat these as equal since the DB does.
  private static void assertVKeysEqual(
      VKey<? extends PollMessage> one, VKey<? extends PollMessage> two) {
    assertThat(
            one.getKind().isAssignableFrom(two.getKind())
                || two.getKind().isAssignableFrom(one.getKind()))
        .isTrue();
    assertThat(one.getKey()).isEqualTo(two.getKey());
  }
}
