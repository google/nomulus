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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.Sets.difference;
import static google.registry.model.common.Cursor.CursorType.DOMAIN_WATCHER;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.model.common.Cursor;
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Action that monitors configured TLDs for domain creations and deletions and sends email
 * notifications.
 *
 * <p>Reports three categories of events in each half-open {@code [cursorTime, now - cooldown)}
 * window:
 *
 * <ul>
 *   <li>Newly created domains ({@code Domain.creationTime} in window).
 *   <li>Pending domain deletions initiated in the window ({@code DomainHistory} {@code
 *       DOMAIN_DELETE} with {@code modificationTime} in window and {@code deletionTime >= endTime},
 *       catching non-AGP deletes that immediately drop DNS before their 35-day RGP + pending-delete
 *       purge).
 *   <li>Finalized domain deletions ({@code Domain.deletionTime} in window, catching AGP deletes,
 *       expired domain deletes, and completed pending-delete purges).
 * </ul>
 */
@Action(
    service = Action.Service.BACKEND,
    path = DomainWatcherAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class DomainWatcherAction implements Runnable {

  public static final String PATH = "/_dr/task/domainWatcher";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String LOCK_NAME = "DomainWatcherAction";

  // 15-minute lease avoids locking out retries for an hour if a pod crashes mid-run.
  static final Duration LOCK_LEASE_DURATION = Duration.ofMinutes(15);

  // 15-minute cooldown before now absorbs in-flight EPP commits and Cloud SQL read-replica lag
  // before the cursor advances past the window.
  static final Duration COOLDOWN_DURATION = Duration.ofMinutes(15);

  // Cap rendered domains per section to stay well within Gmail API's 25 MB message limit and
  // prevent bulk spikes from permanently wedging the cursor.
  static final int MAX_DOMAINS_PER_SECTION = 500;

  private static final String SELECT_CREATED_DOMAINS_QUERY =
      """
      SELECT domainName, tld, creationRegistrarId, creationTime.creationTime, deletionTime
      FROM Domain
      WHERE tld IN :tlds
        AND creationTime.creationTime >= :startTime
        AND creationTime.creationTime < :endTime
      ORDER BY tld ASC, creationTime.creationTime ASC, domainName ASC
      """;

  private static final String SELECT_PENDING_DELETIONS_QUERY =
      """
      SELECT resource.domainName, resource.tld, clientId, resource.creationTime.creationTime,
             resource.deletionTime
      FROM DomainHistory
      WHERE type = :deleteType
        AND modificationTime >= :startTime
        AND modificationTime < :endTime
        AND resource.tld IN :tlds
        AND resource.deletionTime >= :endTime
      ORDER BY resource.tld ASC, modificationTime ASC, resource.domainName ASC
      """;

  private static final String SELECT_DELETED_DOMAINS_QUERY =
      """
      SELECT domainName, tld, currentSponsorRegistrarId, creationTime.creationTime, deletionTime
      FROM Domain
      WHERE tld IN :tlds
        AND deletionTime >= :startTime
        AND deletionTime < :endTime
      ORDER BY tld ASC, deletionTime ASC, domainName ASC
      """;

  private final Clock clock;
  private final LockHandler lockHandler;
  private final GmailClient gmailClient;
  private final Response response;
  private final ImmutableSet<String> watchedTlds;
  private final InternetAddress alertRecipientAddress;
  private final int maxDomainsPerSection;

  @Inject
  public DomainWatcherAction(
      Clock clock,
      LockHandler lockHandler,
      GmailClient gmailClient,
      Response response,
      @Config("domainWatcherTlds") ImmutableSet<String> watchedTlds,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress) {
    this(
        clock,
        lockHandler,
        gmailClient,
        response,
        watchedTlds,
        alertRecipientAddress,
        MAX_DOMAINS_PER_SECTION);
  }

  DomainWatcherAction(
      Clock clock,
      LockHandler lockHandler,
      GmailClient gmailClient,
      Response response,
      ImmutableSet<String> watchedTlds,
      InternetAddress alertRecipientAddress,
      int maxDomainsPerSection) {
    this.clock = clock;
    this.lockHandler = lockHandler;
    this.gmailClient = gmailClient;
    this.response = response;
    this.watchedTlds = watchedTlds;
    this.alertRecipientAddress = alertRecipientAddress;
    this.maxDomainsPerSection = maxDomainsPerSection;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    if (watchedTlds.isEmpty()) {
      String message = "No TLDs configured for domain watcher.";
      logger.atInfo().log(message);
      response.setStatus(SC_OK);
      response.setPayload(message);
      return;
    }

    Set<String> unknownTlds = difference(watchedTlds, Tlds.getTlds());
    if (!unknownTlds.isEmpty()) {
      logger.atWarning().log(
          "Domain watcher configured with unknown TLDs (not found in registry): %s", unknownTlds);
    }

    Callable<Void> runner =
        () -> {
          try {
            runLocked();
            response.setStatus(SC_OK);
          } catch (RuntimeException e) {
            logger.atSevere().withCause(e).log("Error running DomainWatcherAction.");
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Error running DomainWatcherAction: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, LOCK_LEASE_DURATION, LOCK_NAME)) {
      String message = "Could not acquire lock; already running?";
      logger.atWarning().log(message);
      // Return 204 so Cloud Tasks does not retry when another instance already holds the lock.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
    }
  }

  private void runLocked() {
    Instant searchEndTime = clock.now().minus(COOLDOWN_DURATION);
    Optional<Instant> maybeStartTime =
        tm().transact(
                () -> {
                  Optional<Cursor> cursor =
                      tm().loadByKeyIfPresent(Cursor.createGlobalVKey(DOMAIN_WATCHER));
                  if (cursor.isEmpty()) {
                    tm().put(Cursor.createGlobal(DOMAIN_WATCHER, searchEndTime));
                    return Optional.empty();
                  }
                  return Optional.of(cursor.get().getCursorTime());
                });

    if (maybeStartTime.isEmpty()) {
      String message =
          String.format(
              "Initialized domain watcher cursor to %s; skipping historical domains.",
              searchEndTime);
      logger.atInfo().log(message);
      response.setPayload(message);
      return;
    }

    Instant searchStartTime = maybeStartTime.get();
    if (!searchEndTime.isAfter(searchStartTime)) {
      String message =
          String.format(
              "Search end time %s is not after cursor time %s; nothing to do.",
              searchEndTime, searchStartTime);
      logger.atInfo().log(message);
      response.setPayload(message);
      return;
    }

    DomainChanges changes =
        replicaTm()
            .transact(
                TRANSACTION_REPEATABLE_READ,
                () -> queryDomainChanges(searchStartTime, searchEndTime));

    if (!changes.isEmpty()) {
      String subject = formatSubject(changes);
      String body = formatEmailBody(searchStartTime, searchEndTime, changes);
      gmailClient.sendEmail(EmailMessage.create(subject, body, alertRecipientAddress));
    }

    // Intentionally advance the cursor after sending the email (at-least-once delivery): if this
    // DB write fails transiently, Cloud Tasks retries and may send a duplicate email rather than
    // silently missing an alert.
    tm().transact(() -> tm().put(Cursor.createGlobal(DOMAIN_WATCHER, searchEndTime)));

    String message =
        String.format(
            "Domain watcher processed window [%s, %s): %s created, %s pending deletions, %s"
                + " finalized deletions.",
            searchStartTime,
            searchEndTime,
            changes.created().formattedCount(),
            changes.pendingDeletions().formattedCount(),
            changes.finalizedDeletions().formattedCount());
    logger.atInfo().log(message);
    response.setPayload(message);
  }

  private DomainChanges queryDomainChanges(Instant startTime, Instant endTime) {
    int fetchLimit = maxDomainsPerSection + 1;
    List<Object[]> createdRows =
        replicaTm()
            .query(SELECT_CREATED_DOMAINS_QUERY, Object[].class)
            .setParameter("tlds", watchedTlds)
            .setParameter("startTime", startTime)
            .setParameter("endTime", endTime)
            .setMaxResults(fetchLimit)
            .getResultList();
    List<Object[]> pendingDeletionRows =
        replicaTm()
            .query(SELECT_PENDING_DELETIONS_QUERY, Object[].class)
            .setParameter("deleteType", DOMAIN_DELETE)
            .setParameter("tlds", watchedTlds)
            .setParameter("startTime", startTime)
            .setParameter("endTime", endTime)
            .setMaxResults(fetchLimit)
            .getResultList();
    List<Object[]> deletedRows =
        replicaTm()
            .query(SELECT_DELETED_DOMAINS_QUERY, Object[].class)
            .setParameter("tlds", watchedTlds)
            .setParameter("startTime", startTime)
            .setParameter("endTime", endTime)
            .setMaxResults(fetchLimit)
            .getResultList();
    return new DomainChanges(
        toDomainSection(createdRows),
        toDomainSection(pendingDeletionRows),
        toDomainSection(deletedRows));
  }

  private DomainSection toDomainSection(List<Object[]> rows) {
    boolean truncated = rows.size() > maxDomainsPerSection;
    ImmutableList<DomainSummary> summaries =
        rows.stream()
            .limit(maxDomainsPerSection)
            .map(
                row ->
                    new DomainSummary(
                        (String) row[0],
                        (String) row[1],
                        Objects.toString(row[2], "unknown"),
                        (Instant) row[3],
                        (Instant) row[4]))
            .collect(toImmutableList());
    return new DomainSection(summaries, truncated);
  }

  private static String formatSubject(DomainChanges changes) {
    String env = RegistryEnvironment.get().name().toLowerCase(Locale.ROOT);
    if (changes.pendingDeletions().isEmpty()) {
      return String.format(
          "Domain watcher report for %s (%s created, %s deleted)",
          env, changes.created().formattedCount(), changes.finalizedDeletions().formattedCount());
    }
    return String.format(
        "Domain watcher report for %s (%s created, %s pending delete, %s deleted)",
        env,
        changes.created().formattedCount(),
        changes.pendingDeletions().formattedCount(),
        changes.finalizedDeletions().formattedCount());
  }

  private static String formatEmailBody(Instant startTime, Instant endTime, DomainChanges changes) {
    StringBuilder body = new StringBuilder();
    body.append(String.format("Domain watcher report (%s to %s)\n", startTime, endTime));
    if (!changes.created().isEmpty()) {
      appendSection(body, "Newly created domains", changes.created(), endTime);
    }
    if (!changes.pendingDeletions().isEmpty()) {
      appendSection(body, "Pending domain deletions", changes.pendingDeletions(), endTime);
    }
    if (!changes.finalizedDeletions().isEmpty()) {
      appendSection(body, "Finalized domain deletions", changes.finalizedDeletions(), endTime);
    }
    return body.toString();
  }

  private static void appendSection(
      StringBuilder body, String sectionTitle, DomainSection section, Instant endTime) {
    body.append(
        String.format(
            "\n%s (%s%s):\n",
            sectionTitle, section.formattedCount(), section.truncated() ? ", truncated" : ""));
    ImmutableListMultimap<String, DomainSummary> byTld =
        section.domains().stream().collect(toImmutableListMultimap(DomainSummary::tld, d -> d));
    for (String tld : byTld.keySet()) {
      ImmutableList<DomainSummary> tldDomains = byTld.get(tld);
      body.append(String.format("\n.%s (%d):\n", tld, tldDomains.size()));
      for (DomainSummary domain : tldDomains) {
        body.append(
            String.format(
                "  - %s (registrar: %s, created: %s",
                domain.domainName(), domain.registrarId(), domain.creationTime()));
        if (domain.deletionTime().isBefore(END_INSTANT)) {
          String deletionLabel =
              domain.deletionTime().isAfter(endTime) ? "scheduled deletion" : "deleted";
          body.append(String.format(", %s: %s", deletionLabel, domain.deletionTime()));
        }
        body.append(")\n");
      }
    }
    if (section.truncated()) {
      body.append(
          String.format("\n... results truncated at %d domains.\n", section.domains().size()));
    }
  }

  private record DomainSummary(
      String domainName,
      String tld,
      String registrarId,
      Instant creationTime,
      Instant deletionTime) {}

  private record DomainSection(ImmutableList<DomainSummary> domains, boolean truncated) {
    boolean isEmpty() {
      return domains.isEmpty();
    }

    String formattedCount() {
      return domains.size() + (truncated ? "+" : "");
    }
  }

  private record DomainChanges(
      DomainSection created, DomainSection pendingDeletions, DomainSection finalizedDeletions) {
    boolean isEmpty() {
      return created.isEmpty() && pendingDeletions.isEmpty() && finalizedDeletions.isEmpty();
    }
  }
}
