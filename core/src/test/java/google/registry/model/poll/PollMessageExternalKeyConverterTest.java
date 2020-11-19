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

import com.googlecode.objectify.Key;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessageExternalKeyConverter.PollMessageExternalKeyParseException;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PollMessageExternalKeyConverter}. */
public class PollMessageExternalKeyConverterTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension public InjectExtension inject = new InjectExtension();

  private HistoryEntry historyEntry;
  private FakeClock clock = new FakeClock(DateTime.parse("2007-07-07T01:01:01Z"));

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("foobar");
    historyEntry = persistResource(new HistoryEntry.Builder()
      .setParent(persistActiveDomain("foo.foobar"))
      .setType(HistoryEntry.Type.DOMAIN_CREATE)
      .setPeriod(Period.create(1, Period.Unit.YEARS))
      .setXmlBytes("<xml></xml>".getBytes(UTF_8))
      .setModificationTime(clock.nowUtc())
      .setClientId("foo")
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
                .setClientId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("1-2-FOOBAR-4-5-2007");
    assertThat(parsePollMessageExternalId("1-2-FOOBAR-4-5-2007"))
        .isEqualTo(Key.create(pollMessage));
  }

  @Test
  void testSuccess_contact() {
    historyEntry =
        persistResource(historyEntry.asBuilder().setParent(persistActiveContact("tim")).build());
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setClientId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("2-5-ROID-4-6-2007");
    assertThat(parsePollMessageExternalId("2-5-ROID-4-6-2007")).isEqualTo(Key.create(pollMessage));
  }

  @Test
  void testSuccess_host() {
    historyEntry =
        persistResource(historyEntry.asBuilder().setParent(persistActiveHost("time.zyx")).build());
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setClientId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .build());
    assertThat(makePollMessageExternalId(pollMessage)).isEqualTo("3-5-ROID-4-6-2007");
    assertThat(parsePollMessageExternalId("3-5-ROID-4-6-2007")).isEqualTo(Key.create(pollMessage));
  }

  @Test
  void testFailure_missingYearField() {
    assertThrows(
        PollMessageExternalKeyParseException.class,
        () -> parsePollMessageExternalId("1-2-FOOBAR-4-5"));
  }

  @Test
  void testFailure_invalidEppResourceTypeId() {
    // Populate the testdata correctly as for 1-2-FOOBAR-4-5 so we know that the only thing that
    // is wrong here is the EppResourceTypeId.
    testSuccess_domain();
    assertThrows(
        PollMessageExternalKeyParseException.class,
        () -> parsePollMessageExternalId("4-2-FOOBAR-4-5-2007"));
  }

  @Test
  void testFailure_tooFewComponentParts() {
    assertThrows(
        PollMessageExternalKeyParseException.class,
        () -> parsePollMessageExternalId("1-3-EXAMPLE"));
  }

  @Test
  void testFailure_tooManyComponentParts() {
    assertThrows(
        PollMessageExternalKeyParseException.class,
        () -> parsePollMessageExternalId("1-3-EXAMPLE-4-5-2007-2009"));
  }

  @Test
  void testFailure_nonNumericIds() {
    assertThrows(
        PollMessageExternalKeyParseException.class,
        () -> parsePollMessageExternalId("A-B-FOOBAR-D-E-F"));
  }
}
