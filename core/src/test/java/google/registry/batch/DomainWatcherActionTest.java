// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.DomainWatcherAction.COOLDOWN_DURATION;
import static google.registry.batch.DomainWatcherAction.MAX_DOMAINS_PER_SECTION;
import static google.registry.model.common.Cursor.CursorType.DOMAIN_WATCHER;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.common.collect.ImmutableSet;
import google.registry.groups.GmailClient;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link DomainWatcherAction}. */
@ExtendWith(MockitoExtension.class)
class DomainWatcherActionTest {

  private static final Instant INITIAL_TIME = Instant.parse("2026-05-01T12:00:00Z");
  private static final Instant SEARCH_END_TIME = INITIAL_TIME.minus(COOLDOWN_DURATION);
  private static final Instant CURSOR_TIME = SEARCH_END_TIME.minus(Duration.ofDays(1));

  private final FakeClock clock = new FakeClock(INITIAL_TIME);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private GmailClient gmailClient;

  private InternetAddress recipient;
  private FakeResponse response;
  private FakeLockHandler lockHandler;

  @BeforeEach
  void beforeEach() throws Exception {
    createTlds("google", "youtube", "other");
    recipient = new InternetAddress("alerts@example.com");
    response = new FakeResponse();
    lockHandler = new FakeLockHandler(true);
  }

  private DomainWatcherAction createAction(ImmutableSet<String> watchedTlds) {
    return createAction(watchedTlds, MAX_DOMAINS_PER_SECTION);
  }

  private DomainWatcherAction createAction(
      ImmutableSet<String> watchedTlds, int maxDomainsPerSection) {
    return new DomainWatcherAction(
        clock, lockHandler, gmailClient, response, watchedTlds, recipient, maxDomainsPerSection);
  }

  private static Optional<Cursor> loadCursor() {
    return tm().transact(() -> tm().loadByKeyIfPresent(Cursor.createGlobalVKey(DOMAIN_WATCHER)));
  }

  @Test
  void testEmptyWatchedTlds_noop() {
    createAction(ImmutableSet.of()).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("No TLDs configured");
    assertThat(loadCursor()).isEmpty();
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testLockContention_returnsNoContent() {
    lockHandler = new FakeLockHandler(false);
    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).contains("Could not acquire lock");
    assertThat(loadCursor()).isEmpty();
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testFirstRun_initializesCursorAndSkipsHistoricalDomains() {
    // Created well before cooldown so it would be reported if the cursor defaulted to epoch.
    persistResource(
        newDomain("historical.google")
            .asBuilder()
            .setCreationTimeForTest(INITIAL_TIME.minus(Duration.ofDays(1)))
            .build());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("Initialized domain watcher cursor");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testSearchEndEqualToCursor_noop() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, SEARCH_END_TIME));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("not after cursor time");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testSearchEndBeforeCursor_noop() {
    Instant futureCursor = SEARCH_END_TIME.plus(Duration.ofMinutes(10));
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, futureCursor));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("not after cursor time");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(futureCursor);
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testNoChangesInWindow_advancesCursorWithoutEmail() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testGroupingAndOrdering_acrossMultipleTlds() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    Instant firstTime = CURSOR_TIME.plus(Duration.ofHours(1));
    Instant secondTime = CURSOR_TIME.plus(Duration.ofHours(2));
    Instant thirdTime = CURSOR_TIME.plus(Duration.ofHours(3));

    // Persist out of order across TLDs and timestamps.
    persistResource(
        newDomain("later.youtube").asBuilder().setCreationTimeForTest(thirdTime).build());
    persistResource(
        newDomain("third.google").asBuilder().setCreationTimeForTest(thirdTime).build());
    persistResource(
        newDomain("second.google").asBuilder().setCreationTimeForTest(secondTime).build());
    persistResource(
        newDomain("first.google").asBuilder().setCreationTimeForTest(firstTime).build());
    persistResource(
        newDomain("earlier.youtube").asBuilder().setCreationTimeForTest(secondTime).build());

    // Pass watchedTlds in reverse alphabetical order to verify SQL ORDER BY tld ASC.
    createAction(ImmutableSet.of("youtube", "google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);

    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.recipients()).containsExactly(recipient);
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (5 created, 0 deleted)");
    assertThat(email.body())
        .isEqualTo(
            """
            Domain watcher report (2026-04-30T11:45:00Z to 2026-05-01T11:45:00Z)

            Newly created domains (5):

            .google (3):
              - first.google (registrar: TheRegistrar, created: 2026-04-30T12:45:00Z)
              - second.google (registrar: TheRegistrar, created: 2026-04-30T13:45:00Z)
              - third.google (registrar: TheRegistrar, created: 2026-04-30T14:45:00Z)

            .youtube (2):
              - earlier.youtube (registrar: TheRegistrar, created: 2026-04-30T13:45:00Z)
              - later.youtube (registrar: TheRegistrar, created: 2026-04-30T14:45:00Z)
            """);
  }

  @Test
  void testExactWindowBoundaries_halfOpenIntervalForCreationsAndDeletions() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    Instant oldCreation = CURSOR_TIME.minus(Duration.ofDays(10));

    // Included: creationTime == startTime and deletionTime == startTime.
    persistResource(
        newDomain("created-at-start.google")
            .asBuilder()
            .setCreationTimeForTest(CURSOR_TIME)
            .build());
    persistResource(
        newDomain("deleted-at-start.google")
            .asBuilder()
            .setCreationTimeForTest(oldCreation)
            .setDeletionTime(CURSOR_TIME)
            .build());

    // Excluded (historical): deletionTime < startTime.
    persistResource(
        newDomain("deleted-before-start.google")
            .asBuilder()
            .setCreationTimeForTest(oldCreation)
            .setDeletionTime(CURSOR_TIME.minusSeconds(1))
            .build());

    // Excluded on run 1, included on run 2: creationTime == endTime and deletionTime == endTime.
    persistResource(
        newDomain("created-at-end.google")
            .asBuilder()
            .setCreationTimeForTest(SEARCH_END_TIME)
            .build());
    persistResource(
        newDomain("deleted-at-end.google")
            .asBuilder()
            .setCreationTimeForTest(oldCreation)
            .setDeletionTime(SEARCH_END_TIME)
            .build());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);

    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage firstEmail = emailCaptor.getValue();
    assertThat(firstEmail.subject())
        .isEqualTo("Domain watcher report for unittest (1 created, 1 deleted)");
    assertThat(firstEmail.body()).contains("created-at-start.google");
    assertThat(firstEmail.body()).contains("deleted-at-start.google");
    assertThat(firstEmail.body()).doesNotContain("deleted-before-start.google");
    assertThat(firstEmail.body()).doesNotContain("created-at-end.google");
    assertThat(firstEmail.body()).doesNotContain("deleted-at-end.google");

    // Advance clock by 10 minutes: the exact-endTime domains must now be picked up on run 2.
    Instant secondRunTime = INITIAL_TIME.plus(Duration.ofMinutes(10));
    clock.setTo(secondRunTime);
    response = new FakeResponse();
    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime))
        .hasValue(secondRunTime.minus(COOLDOWN_DURATION));

    ArgumentCaptor<EmailMessage> secondCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient, times(2)).sendEmail(secondCaptor.capture());
    EmailMessage secondEmail = secondCaptor.getAllValues().get(1);
    assertThat(secondEmail.subject())
        .isEqualTo("Domain watcher report for unittest (1 created, 1 deleted)");
    assertThat(secondEmail.body()).contains("created-at-end.google");
    assertThat(secondEmail.body()).contains("deleted-at-end.google");
    assertThat(secondEmail.body()).doesNotContain("created-at-start.google");
    assertThat(secondEmail.body()).doesNotContain("deleted-at-start.google");
  }

  @Test
  void testPendingDeletionsAndScheduledDeletionLabel() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    Instant futurePurgeTime = INITIAL_TIME.plus(Duration.ofDays(35));

    // Pre-existing domain put into pending delete during the window (non-AGP delete).
    Domain pendingDeleteDomain =
        persistResource(
            newDomain("pending-delete.google")
                .asBuilder()
                .setCreationTimeForTest(CURSOR_TIME.minus(Duration.ofDays(60)))
                .setDeletionTime(futurePurgeTime)
                .build());
    persistResource(
        new DomainHistory.Builder()
            .setDomain(pendingDeleteDomain)
            .setType(DOMAIN_DELETE)
            .setRegistrarId("NewRegistrar")
            .setModificationTime(CURSOR_TIME.plus(Duration.ofHours(3)))
            .build());

    // Newly created domain that also has a future pending deletion scheduled.
    persistResource(
        newDomain("created-then-pending.google")
            .asBuilder()
            .setCreationTimeForTest(CURSOR_TIME.plus(Duration.ofHours(1)))
            .setDeletionTime(futurePurgeTime)
            .build());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (1 created, 1 pending delete, 0 deleted)");
    assertThat(email.body()).contains("Newly created domains (1):");
    assertThat(email.body())
        .contains(
            "created-then-pending.google (registrar: TheRegistrar, created:"
                + " 2026-04-30T12:45:00Z, scheduled deletion: 2026-06-05T12:00:00Z)");
    assertThat(email.body()).contains("Pending domain deletions (1):");
    assertThat(email.body())
        .contains(
            "pending-delete.google (registrar: NewRegistrar, created: 2026-03-01T11:45:00Z,"
                + " scheduled deletion: 2026-06-05T12:00:00Z)");
    assertThat(email.body()).doesNotContain("Finalized domain deletions");
  }

  @Test
  void testTransferredThenDeletedDomain_reportsCurrentSponsorRegistrarAndIgnoresUnwatched() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    Instant deletionTime = CURSOR_TIME.plus(Duration.ofHours(2));

    // Watched domain created by TheRegistrar, later transferred to NewRegistrar, then deleted.
    persistResource(
        newDomain("transferred.google")
            .asBuilder()
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .setCreationTimeForTest(CURSOR_TIME.minus(Duration.ofDays(30)))
            .setDeletionTime(deletionTime)
            .build());

    // Unwatched TLD creation and deletion (both ignored).
    persistResource(
        newDomain("created-unwatched.other")
            .asBuilder()
            .setCreationTimeForTest(CURSOR_TIME.plus(Duration.ofHours(1)))
            .build());
    persistResource(
        newDomain("deleted-unwatched.other")
            .asBuilder()
            .setCreationTimeForTest(CURSOR_TIME.minus(Duration.ofDays(10)))
            .setDeletionTime(deletionTime)
            .build());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (0 created, 1 deleted)");
    assertThat(email.body()).contains("Finalized domain deletions (1):");
    assertThat(email.body()).contains("transferred.google (registrar: NewRegistrar,");
    assertThat(email.body()).doesNotContain("TheRegistrar");
    assertThat(email.body()).doesNotContain("unwatched");
    assertThat(email.body()).doesNotContain("Newly created domains");
  }

  @Test
  void testTruncation_capsSectionAndAdvancesCursor() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    for (int i = 1; i <= 3; i++) {
      persistResource(
          newDomain(String.format("domain-%d.google", i))
              .asBuilder()
              .setCreationTimeForTest(CURSOR_TIME.plus(Duration.ofMinutes(i)))
              .build());
    }

    createAction(ImmutableSet.of("google"), /* maxDomainsPerSection= */ 2).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(SEARCH_END_TIME);

    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (2+ created, 0 deleted)");
    assertThat(email.body()).contains("Newly created domains (2+, truncated):");
    assertThat(email.body()).contains("domain-1.google");
    assertThat(email.body()).contains("domain-2.google");
    assertThat(email.body()).doesNotContain("domain-3.google");
    assertThat(email.body()).contains("... results truncated at 2 domains.");
  }

  @Test
  void testEmailFailure_returns500AndDoesNotAdvanceCursor() {
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, CURSOR_TIME));
    persistResource(
        newDomain("fail.google")
            .asBuilder()
            .setCreationTimeForTest(CURSOR_TIME.plus(Duration.ofHours(1)))
            .build());

    doThrow(new RuntimeException("Gmail unavailable")).when(gmailClient).sendEmail(any());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("Gmail unavailable");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(CURSOR_TIME);
  }
}
