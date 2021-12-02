// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.reporting.HistoryEntry.Type.SYNTHETIC;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EnqueuePollMessageCommand}. */
@DualDatabaseTest
class EnqueuePollMessageCommandTest extends CommandTestCase<EnqueuePollMessageCommand> {

  @RegisterExtension final InjectExtension inject = new InjectExtension();

  private DomainBase domain;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", fakeClock);
    domain = persistActiveDomain("example.tld");
    fakeClock.advanceOneMilli();
  }

  @TestOfyAndSql
  void testSuccess_domainAndMessage() throws Exception {
    runCommandForced("--domain=example.tld", "--message=This domain is bad");

    HistoryEntry synthetic = getOnlyHistoryEntryOfType(domain, SYNTHETIC);
    assertAboutHistoryEntries()
        .that(synthetic)
        .bySuperuser(true)
        .and()
        .hasMetadataReason("Manual enqueueing of poll message")
        .and()
        .hasNoXml()
        .and()
        .hasRegistrarId("TheRegistrar")
        .and()
        .hasModificationTime(fakeClock.nowUtc())
        .and()
        .hasMetadataRequestedByRegistrar(false);
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.OneTime.Builder()
            .setParent(synthetic)
            .setMsg("This domain is bad")
            .setRegistrarId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .build());
  }

  @TestOfyAndSql
  void testSuccess_specifyClientId() throws Exception {
    runCommandForced(
        "--domain=example.tld", "--message=This domain needs work", "--client=NewRegistrar");

    HistoryEntry synthetic = getOnlyHistoryEntryOfType(domain, SYNTHETIC);
    assertAboutHistoryEntries()
        .that(synthetic)
        .bySuperuser(true)
        .and()
        .hasMetadataReason("Manual enqueueing of poll message")
        .and()
        .hasNoXml()
        .and()
        .hasRegistrarId("NewRegistrar")
        .and()
        .hasModificationTime(fakeClock.nowUtc())
        .and()
        .hasMetadataRequestedByRegistrar(false);
    assertPollMessages(
        "NewRegistrar",
        new PollMessage.OneTime.Builder()
            .setParent(synthetic)
            .setMsg("This domain needs work")
            .setRegistrarId("NewRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .build());
  }

  @TestOfyAndSql
  void testNonexistentDomain() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--domain=example2.tld", "--message=This domain needs help"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain example2.tld doesn't exist or isn't active");
  }

  @TestOfyAndSql
  void testDomainIsRequired() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> runCommandForced("--message=Foo bar"));
    assertThat(thrown).hasMessageThat().contains("The following option is required: -d, --domain");
  }

  @TestOfyAndSql
  void testMessageIsRequired() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> runCommandForced("--domain=example.tld"));
    assertThat(thrown).hasMessageThat().contains("The following option is required: -m, --message");
  }
}
