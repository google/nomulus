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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.util.DateTimeUtils.minusMinutes;
import static google.registry.util.DateTimeUtils.plusMinutes;

import google.registry.model.domain.Domain;
import google.registry.model.domain.Period;
import google.registry.model.reporting.HistoryEntry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetClaimsListCommand}. */
class GetHistoryEntriesCommandTest extends CommandTestCase<GetHistoryEntriesCommand> {

  private Domain domain;

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(Instant.parse("2000-01-01T00:00:00Z"));
    command.clock = fakeClock;
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
            fakeClock.now()));
    runCommand("--id=example.tld", "--type=DOMAIN");
    assertStdoutIs(
        """
        Client: TheRegistrar
        Time: 2000-01-01T00:00:00Z
        Client TRID: ABC-123
        Server TRID: server-trid
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <xml/>

        """);
  }

  @Test
  void testSuccess_nothingBefore() throws Exception {
    persistResource(
        makeHistoryEntry(
            domain,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            fakeClock.now()));
    runCommand("--before", minusMinutes(fakeClock.now(), 1).toString());
    assertStdoutIs("");
  }

  @Test
  void testSuccess_nothingAfter() throws Exception {
    persistResource(
        makeHistoryEntry(
            domain,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            fakeClock.now()));
    runCommand("--after", plusMinutes(fakeClock.now(), 1).toString());
    assertStdoutIs("");
  }

  @Test
  void testSuccess_withinRange() throws Exception {
    persistResource(
        makeHistoryEntry(
            domain,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            fakeClock.now()));
    runCommand(
        "--after",
        minusMinutes(fakeClock.now(), 1).toString(),
        "--before",
        plusMinutes(fakeClock.now(), 1).toString());
    assertStdoutIs(
        """
        Client: TheRegistrar
        Time: 2000-01-01T00:00:00Z
        Client TRID: ABC-123
        Server TRID: server-trid
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <xml/>

        """);
  }

  @Test
  void testSuccess_noTrid() throws Exception {
    persistResource(
        makeHistoryEntry(
                domain,
                HistoryEntry.Type.DOMAIN_CREATE,
                Period.create(1, Period.Unit.YEARS),
                "created",
                fakeClock.now())
            .asBuilder()
            .setTrid(null)
            .build());
    runCommand("--id=example.tld", "--type=DOMAIN");
    assertStdoutIs(
        """
        Client: TheRegistrar
        Time: 2000-01-01T00:00:00Z
        Client TRID: null
        Server TRID: null
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <xml/>

        """);
  }
}
