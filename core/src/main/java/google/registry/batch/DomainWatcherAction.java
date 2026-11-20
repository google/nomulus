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
import static google.registry.model.common.Cursor.CursorType.DOMAIN_WATCHER;
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
import java.util.concurrent.Callable;

/**
 * Action that monitors configured TLDs for newly created and deleted domains and sends email
 * notifications.
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

  // Cooldown buffer before now so in-flight EPP transactions commit before the cursor advances.
  static final Duration COOLDOWN_DURATION = Duration.ofMinutes(5);

  private static final String SELECT_CREATED_DOMAINS_QUERY =
      """
      SELECT domainName, tld, creationRegistrarId, creationTime.creationTime, deletionTime
      FROM Domain
      WHERE tld IN :tlds
        AND creationTime.creationTime >= :startTime
        AND creationTime.creationTime < :endTime
      ORDER BY tld ASC, creationTime.creationTime ASC, domainName ASC
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

  @Inject
  public DomainWatcherAction(
      Clock clock,
      LockHandler lockHandler,
      GmailClient gmailClient,
      Response response,
      @Config("domainWatcherTlds") ImmutableSet<String> watchedTlds,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress) {
    this.clock = clock;
    this.lockHandler = lockHandler;
    this.gmailClient = gmailClient;
    this.response = response;
    this.watchedTlds = watchedTlds;
    this.alertRecipientAddress = alertRecipientAddress;
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

    Callable<Void> runner =
        () -> {
          try {
            runLocked();
            response.setStatus(SC_OK);
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error running DomainWatcherAction.");
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Error running DomainWatcherAction: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, Duration.ofHours(1), LOCK_NAME)) {
      // Return 204 so Cloud Tasks does not retry when another instance already holds the lock.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("Could not acquire lock; already running?");
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
      String subject =
          String.format(
              "Domain watcher report for %s (%d created, %d deleted)",
              RegistryEnvironment.get().name().toLowerCase(Locale.ROOT),
              changes.created().size(),
              changes.deleted().size());
      String body = formatEmailBody(searchStartTime, searchEndTime, changes);
      gmailClient.sendEmail(EmailMessage.create(subject, body, alertRecipientAddress));
    }

    tm().transact(() -> tm().put(Cursor.createGlobal(DOMAIN_WATCHER, searchEndTime)));

    String message =
        String.format(
            "Domain watcher processed window [%s, %s): %d created, %d deleted.",
            searchStartTime, searchEndTime, changes.created().size(), changes.deleted().size());
    logger.atInfo().log(message);
    response.setPayload(message);
  }

  private DomainChanges queryDomainChanges(Instant startTime, Instant endTime) {
    List<Object[]> createdRows =
        replicaTm()
            .query(SELECT_CREATED_DOMAINS_QUERY, Object[].class)
            .setParameter("tlds", watchedTlds)
            .setParameter("startTime", startTime)
            .setParameter("endTime", endTime)
            .getResultList();
    List<Object[]> deletedRows =
        replicaTm()
            .query(SELECT_DELETED_DOMAINS_QUERY, Object[].class)
            .setParameter("tlds", watchedTlds)
            .setParameter("startTime", startTime)
            .setParameter("endTime", endTime)
            .getResultList();
    return new DomainChanges(toDomainSummaries(createdRows), toDomainSummaries(deletedRows));
  }

  private static ImmutableList<DomainSummary> toDomainSummaries(List<Object[]> rows) {
    return rows.stream()
        .map(
            row ->
                new DomainSummary(
                    (String) row[0],
                    (String) row[1],
                    Objects.toString(row[2], "unknown"),
                    (Instant) row[3],
                    (Instant) row[4]))
        .collect(toImmutableList());
  }

  private static String formatEmailBody(Instant startTime, Instant endTime, DomainChanges changes) {
    StringBuilder body = new StringBuilder();
    body.append(String.format("Domain watcher report (%s to %s)\n", startTime, endTime));
    if (!changes.created().isEmpty()) {
      appendSection(body, "Newly created domains", changes.created());
    }
    if (!changes.deleted().isEmpty()) {
      appendSection(body, "Newly deleted domains", changes.deleted());
    }
    return body.toString();
  }

  private static void appendSection(
      StringBuilder body, String sectionTitle, ImmutableList<DomainSummary> domains) {
    body.append(String.format("\n%s (%d):\n", sectionTitle, domains.size()));
    ImmutableListMultimap<String, DomainSummary> byTld =
        domains.stream().collect(toImmutableListMultimap(DomainSummary::tld, d -> d));
    for (String tld : byTld.keySet()) {
      ImmutableList<DomainSummary> tldDomains = byTld.get(tld);
      body.append(String.format("\n.%s (%d):\n", tld, tldDomains.size()));
      for (DomainSummary domain : tldDomains) {
        body.append(
            String.format(
                "  - %s (registrar: %s, created: %s",
                domain.domainName(), domain.registrarId(), domain.creationTime()));
        if (domain.deletionTime().isBefore(END_INSTANT)) {
          body.append(String.format(", deleted: %s", domain.deletionTime()));
        }
        body.append(")\n");
      }
    }
  }

  private record DomainSummary(
      String domainName,
      String tld,
      String registrarId,
      Instant creationTime,
      Instant deletionTime) {}

  private record DomainChanges(
      ImmutableList<DomainSummary> created, ImmutableList<DomainSummary> deleted) {
    boolean isEmpty() {
      return created.isEmpty() && deleted.isEmpty();
    }
  }
}
