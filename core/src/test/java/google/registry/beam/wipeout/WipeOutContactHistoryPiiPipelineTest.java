// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.wipeout;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_CREATE;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.hibernate.cfg.AvailableSettings.ISOLATION;

import com.google.common.collect.ImmutableList;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WipeOutContactHistoryPiiPipeline}. */
public class WipeOutContactHistoryPiiPipelineTest {

  private static final int MIN_AGE_IN_MONTHS = 18;
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T12:34:56Z"));
  private final WipeOutContactHistoryPiiPipelineOptions options =
      PipelineOptionsFactory.create().as(WipeOutContactHistoryPiiPipelineOptions.class);
  private Contact contact;

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(clock)
          .withProperty(ISOLATION, TRANSACTION_REPEATABLE_READ.name())
          .buildIntegrationTestExtension();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @BeforeEach
  void beforeEach() {
    contact =
        persistResource(
            newContact("my-contact1")
                .asBuilder()
                .setEmailAddress("test@example.com")
                .setFaxNumber(
                    new ContactPhoneNumber.Builder().setPhoneNumber("+12122122122").build())
                .build());
    // T = 0 month;
    persistResource(createHistory(contact));
    // T = 10 months;
    advanceMonths(10);
    persistResource(createHistory(contact));
    // T = 30 months;
    advanceMonths(20);
    options.setCutoffTime(DATE_TIME_FORMATTER.print(clock.nowUtc().minusMonths(MIN_AGE_IN_MONTHS)));
  }

  @Test
  void testSuccess() {
    WipeOutContactHistoryPiiPipeline wipeOutContactHistoryPiiPipeline =
        new WipeOutContactHistoryPiiPipeline(options);
    wipeOutContactHistoryPiiPipeline.run(pipeline).waitUntilFinish();
    ImmutableList<ContactHistory> histories =
        HistoryEntryDao.loadHistoryObjectsForResource(contact.createVKey(), ContactHistory.class);
    assertThat(histories.size()).isEqualTo(2);
    ImmutableList<ContactHistory> wipedEntries =
        histories.stream()
            .filter(e -> e.getContactBase().get().getEmailAddress() == null)
            .collect(toImmutableList());
    // Only the history entry at T = 10 is wiped. The one at T = 10 is over 18 months old, but it
    // is the most recent entry, so it is kept.
    assertThat(wipedEntries.size()).isEqualTo(1);
    assertThat(wipedEntries.get(0).getContactBase().get().getFaxNumber()).isNull();
    // With a new history entry at T = 30, the one at T = 10 is eligible for wipe out. Note the
    // current time itself (therefore the cutoff time) has not changed.
    persistResource(createHistory(contact));
    wipeOutContactHistoryPiiPipeline.run(pipeline).waitUntilFinish();
    histories =
        HistoryEntryDao.loadHistoryObjectsForResource(contact.createVKey(), ContactHistory.class);
    assertThat(histories.size()).isEqualTo(3);
    wipedEntries =
        histories.stream()
            .filter(e -> e.getContactBase().get().getEmailAddress() == null)
            .collect(toImmutableList());
    assertThat(wipedEntries.size()).isEqualTo(2);
  }

  @Test
  void testSuccess_dryRun() {
    options.setIsDryRun(true);
    WipeOutContactHistoryPiiPipeline wipeOutContactHistoryPiiPipeline =
        new WipeOutContactHistoryPiiPipeline(options);
    wipeOutContactHistoryPiiPipeline.run(pipeline).waitUntilFinish();
    ImmutableList<ContactHistory> histories =
        HistoryEntryDao.loadHistoryObjectsForResource(contact.createVKey(), ContactHistory.class);
    assertThat(histories.size()).isEqualTo(2);
    assertThat(
            histories.stream()
                .filter(e -> e.getContactBase().get().getEmailAddress() == null)
                .collect(toImmutableList()))
        .isEmpty();
  }

  private ContactHistory createHistory(Contact contact) {
    return new ContactHistory.Builder()
        .setContact(contact)
        .setType(CONTACT_CREATE)
        .setRegistrarId("TheRegistrar")
        .setModificationTime(clock.nowUtc())
        .build();
  }

  private void advanceMonths(int months) {
    DateTime now = clock.nowUtc();
    DateTime next = now.plusMonths(months);
    clock.advanceBy(new Duration(now, next));
  }
}
