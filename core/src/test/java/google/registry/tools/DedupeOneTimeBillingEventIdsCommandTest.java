// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DedupeOneTimeBillingEventIdsCommand}.
 *
 * <p>Note that these are _not_ dual database tests even though the action has been converted. The
 * dedupe was strictly a one-time event that needed to be done prior to moving to SQL. It should no
 * longer be necessary and we may want to simply remove the command.
 */
class DedupeOneTimeBillingEventIdsCommandTest
    extends CommandTestCase<DedupeOneTimeBillingEventIdsCommand> {

  DomainBase domain;
  DomainHistory historyEntry;
  PollMessage.Autorenew autorenewToResave;
  BillingEvent.OneTime billingEventToResave;

  @BeforeEach
  void beforeEach() {
    createTld("foobar");
    domain = persistActiveDomain("foo.foobar");
    historyEntry = persistHistoryEntry(domain);
    autorenewToResave = persistAutorenewPollMessage(historyEntry);
    billingEventToResave = persistBillingEvent(historyEntry);
  }

  @Test
  void resaveBillingEvent_succeeds() throws Exception {
    runCommand(
        "--force",
        "--key_paths_file",
        writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(billingEventToResave)));

    int count = 0;
    for (BillingEvent.OneTime billingEvent : loadAllOf(BillingEvent.OneTime.class)) {
      count++;
      assertThat(billingEvent.getId()).isNotEqualTo(billingEventToResave.getId());
      assertThat(billingEvent.asBuilder().setId(billingEventToResave.getId()).build())
          .isEqualTo(billingEventToResave);
    }
    assertThat(count).isEqualTo(1);
  }

  @Test
  void resaveBillingEvent_failsWhenReferredByDomain() {
    persistResource(
        domain
            .asBuilder()
            .setTransferData(
                new DomainTransferData.Builder()
                    .setServerApproveEntities(ImmutableSet.of(billingEventToResave.createVKey()))
                    .build())
            .build());

    assertThrows(
        IllegalStateException.class,
        () ->
            runCommand(
                "--force",
                "--key_paths_file",
                writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(billingEventToResave))));
  }

  private PollMessage.Autorenew persistAutorenewPollMessage(HistoryEntry historyEntry) {
    return persistResource(
        new PollMessage.Autorenew.Builder()
            .setRegistrarId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setParent(historyEntry)
            .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
            .setTargetId("foobar.foo")
            .build());
  }

  private BillingEvent.OneTime persistBillingEvent(DomainHistory historyEntry) {
    return persistResource(
        new BillingEvent.OneTime.Builder()
            .setRegistrarId("a registrar")
            .setTargetId("foo.tld")
            .setParent(historyEntry)
            .setReason(Reason.CREATE)
            .setFlags(ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT))
            .setPeriodYears(2)
            .setCost(Money.of(USD, 1))
            .setEventTime(fakeClock.nowUtc())
            .setBillingTime(fakeClock.nowUtc().plusDays(5))
            .build());
  }

  private DomainHistory persistHistoryEntry(DomainBase parent) {
    return persistResource(
        new DomainHistory.Builder()
            .setDomain(parent)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setRegistrarId("foo")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(false)
            .build());
  }

  private static String getKeyPathLiteral(Object entity) {
    Key<?> key = Key.create(entity);
    return String.format(
        "\"DomainBase\", \"%s\", \"HistoryEntry\", %s, \"%s\", %s",
        key.getParent().getParent().getName(), key.getParent().getId(), key.getKind(), key.getId());
  }
}
