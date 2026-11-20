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
import static google.registry.model.common.Cursor.CursorType.DOMAIN_WATCHER;
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
    return new DomainWatcherAction(
        clock, lockHandler, gmailClient, response, watchedTlds, recipient);
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
  void testFirstRun_initializesCursorWithoutSendingEmail() {
    persistResource(newDomain("existing.google"));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("Initialized domain watcher cursor");
    assertThat(loadCursor().map(Cursor::getCursorTime))
        .hasValue(INITIAL_TIME.minus(COOLDOWN_DURATION));
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testSearchEndNotAfterCursor_noop() {
    Instant cursorTime = INITIAL_TIME.minus(COOLDOWN_DURATION);
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, cursorTime));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("not after cursor time");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(cursorTime);
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testNoChangesInWindow_advancesCursorWithoutEmail() {
    Instant cursorTime = INITIAL_TIME.minus(Duration.ofDays(1));
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, cursorTime));

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadCursor().map(Cursor::getCursorTime))
        .hasValue(INITIAL_TIME.minus(COOLDOWN_DURATION));
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testCreatedAndDeletedDomains_groupedByTldAndIgnoresUnwatchedAndCooldown() {
    // Set cursor to 1 day before INITIAL_TIME (minus cooldown).
    Instant cursorTime = INITIAL_TIME.minus(Duration.ofDays(1)).minus(COOLDOWN_DURATION);
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, cursorTime));

    // Pre-existing domain that gets deleted during the window.
    Instant oldDomainCreationTime = cursorTime.minus(Duration.ofDays(10));
    Instant oldDomainDeletionTime = cursorTime.plus(Duration.ofHours(5));
    persistResource(
        newDomain("retired.google")
            .asBuilder()
            .setCreationTimeForTest(oldDomainCreationTime)
            .setDeletionTime(oldDomainDeletionTime)
            .build());

    // Pre-existing domain with future pending deletion (outside window -> ignored).
    persistResource(
        newDomain("pending-delete.google")
            .asBuilder()
            .setCreationTimeForTest(oldDomainCreationTime)
            .setDeletionTime(INITIAL_TIME.plus(Duration.ofDays(2)))
            .build());

    // Newly created active domain on watched TLD.
    persistResource(
        newDomain("alpha.google")
            .asBuilder()
            .setCreationTimeForTest(cursorTime.plus(Duration.ofHours(1)))
            .build());

    // Newly created domain on second watched TLD that was also deleted in the window.
    Instant quickDeleteTime = cursorTime.plus(Duration.ofHours(3));
    persistResource(
        newDomain("ephemeral.youtube")
            .asBuilder()
            .setCreationTimeForTest(cursorTime.plus(Duration.ofHours(2)))
            .setDeletionTime(quickDeleteTime)
            .build());

    // Newly created domain on unwatched TLD (ignored).
    persistResource(
        newDomain("ignored.other")
            .asBuilder()
            .setCreationTimeForTest(cursorTime.plus(Duration.ofHours(4)))
            .build());

    // Newly created domain inside the 5-minute cooldown window (deferred to next run).
    persistResource(
        newDomain("too-recent.google")
            .asBuilder()
            .setCreationTimeForTest(INITIAL_TIME.minus(Duration.ofMinutes(2)))
            .build());

    createAction(ImmutableSet.of("google", "youtube")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    Instant expectedNewCursor = INITIAL_TIME.minus(COOLDOWN_DURATION);
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(expectedNewCursor);

    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.recipients()).containsExactly(recipient);
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (2 created, 2 deleted)");
    assertThat(email.body())
        .isEqualTo(
            """
            Domain watcher report (2026-04-30T11:55:00Z to 2026-05-01T11:55:00Z)

            Newly created domains (2):

            .google (1):
              - alpha.google (registrar: TheRegistrar, created: 2026-04-30T12:55:00Z)

            .youtube (1):
              - ephemeral.youtube (registrar: TheRegistrar, created: 2026-04-30T13:55:00Z, \
            deleted: 2026-04-30T14:55:00Z)

            Newly deleted domains (2):

            .google (1):
              - retired.google (registrar: TheRegistrar, created: 2026-04-20T11:55:00Z, \
            deleted: 2026-04-30T16:55:00Z)

            .youtube (1):
              - ephemeral.youtube (registrar: TheRegistrar, created: 2026-04-30T13:55:00Z, \
            deleted: 2026-04-30T14:55:00Z)
            """);

    // Advance clock past the cooldown window and verify the deferred domain is picked up next run.
    clock.setTo(INITIAL_TIME.plus(Duration.ofMinutes(10)));
    response = new FakeResponse();
    createAction(ImmutableSet.of("google", "youtube")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ArgumentCaptor<EmailMessage> secondEmailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient, times(2)).sendEmail(secondEmailCaptor.capture());
    EmailMessage secondEmail = secondEmailCaptor.getAllValues().get(1);
    assertThat(secondEmail.subject())
        .isEqualTo("Domain watcher report for unittest (1 created, 0 deleted)");
    assertThat(secondEmail.body()).contains("too-recent.google");
    assertThat(secondEmail.body()).doesNotContain("Newly deleted domains");
  }

  @Test
  void testDeletedOnly_omitsCreatedSection() {
    Instant cursorTime = INITIAL_TIME.minus(Duration.ofDays(1));
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, cursorTime));

    persistResource(
        newDomain("deleted-only.google")
            .asBuilder()
            .setCreationTimeForTest(cursorTime.minus(Duration.ofDays(30)))
            .setDeletionTime(cursorTime.plus(Duration.ofHours(2)))
            .build());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage email = emailCaptor.getValue();
    assertThat(email.subject())
        .isEqualTo("Domain watcher report for unittest (0 created, 1 deleted)");
    assertThat(email.body()).contains("Newly deleted domains (1):");
    assertThat(email.body()).contains("deleted-only.google");
    assertThat(email.body()).doesNotContain("Newly created domains");
  }

  @Test
  void testEmailFailure_returns500AndDoesNotAdvanceCursor() {
    Instant cursorTime = INITIAL_TIME.minus(Duration.ofDays(1));
    persistResource(Cursor.createGlobal(DOMAIN_WATCHER, cursorTime));
    persistResource(
        newDomain("fail.google")
            .asBuilder()
            .setCreationTimeForTest(cursorTime.plus(Duration.ofHours(1)))
            .build());

    doThrow(new RuntimeException("Gmail unavailable")).when(gmailClient).sendEmail(any());

    createAction(ImmutableSet.of("google")).run();

    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("Gmail unavailable");
    assertThat(loadCursor().map(Cursor::getCursorTime)).hasValue(cursorTime);
  }
}
