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

package google.registry.tools;

import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;

import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetClaimsListCommand}. */
class GetHistoryEntriesCommandTest extends CommandTestCase<GetHistoryEntriesCommand> {

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01T00:00:00Z"));

  private DomainBase domain;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    domain = persistActiveDomain("example.tld");
  }

  @Test
  void testSuccess_works() throws Exception {
    persistResource(
        makeHistoryEntry(
            domain,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    runCommand("--id=example.tld", "--type=DOMAIN");
    assertStdoutIs(
        "Client: foo\n"
            + "Time: 2000-01-01T00:00:00.000Z\n"
            + "Client TRID: ABC-123\n"
            + "Server TRID: server-trid\n"
            + "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<xml/>\n"
            + "\n");
  }

  @Test
  void testSuccess_noTrid() throws Exception {
    persistResource(
        makeHistoryEntry(
                domain,
                HistoryEntry.Type.DOMAIN_CREATE,
                Period.create(1, Period.Unit.YEARS),
                "created",
                clock.nowUtc())
            .asBuilder()
            .setTrid(null)
            .build());
    runCommand("--id=example.tld", "--type=DOMAIN");
    assertStdoutIs(
        "Client: foo\n"
            + "Time: 2000-01-01T00:00:00.000Z\n"
            + "Client TRID: null\n"
            + "Server TRID: null\n"
            + "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<xml/>\n"
            + "\n");
  }
}
