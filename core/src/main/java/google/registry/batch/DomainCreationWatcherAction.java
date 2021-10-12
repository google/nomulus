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

package google.registry.batch;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.model.common.Cursor.CursorType.DOMAIN_CREATION_WATCHER;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.common.Cursor;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.SendEmailService;
import java.util.List;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** An action that emails the registry about domain creations, typically those on closed TLDs. */
@Action(
    service = Action.Service.BACKEND,
    path = DomainCreationWatcherAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class DomainCreationWatcherAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String PATH = "/_dr/task/domainCreationWatcher";
  static final String LOCK_NAME = "Domain creation watcher";

  private final SendEmailService sendEmailService;
  private final String domainCreationWatcherEmailSubject;
  private final String domainCreationWatcherEmailBody;
  private final ImmutableSet<String> domainCreationWatcherTlds;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final LockHandler lockHandler;
  private final Clock clock;
  private final Response response;

  @Inject
  public DomainCreationWatcherAction(
      @Config("domainCreationWatcherEmailSubject") String domainCreationWatcherEmailSubject,
      @Config("domainCreationWatcherEmailBody") String domainCreationWatcherEmailBody,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      ImmutableSet<String> domainCreationWatcherTlds,
      SendEmailService sendEmailService,
      LockHandler lockHandler,
      Clock clock,
      Response response) {
    this.domainCreationWatcherEmailSubject = domainCreationWatcherEmailSubject;
    this.domainCreationWatcherEmailBody = domainCreationWatcherEmailBody;
    this.domainCreationWatcherTlds = domainCreationWatcherTlds;
    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.lockHandler = lockHandler;
    this.clock = clock;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);

    Callable<Void> runner =
        () -> {
          try {
            jpaTm().transact(this::runLocked);
            response.setStatus(HttpServletResponse.SC_OK);
            // TODO: Set payload.
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Errored out during execution.");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Errored out with cause: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, Duration.standardHours(1), LOCK_NAME)) {
      // Send a 200-series status code to prevent this conflicting action from retrying.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("Could not acquire lock; already running?");
    }
  }

  @VisibleForTesting
  void runLocked() {
    DateTime searchStartTime =
        jpaTm()
            .loadByKeyIfPresent(Cursor.createGlobalVKey(DOMAIN_CREATION_WATCHER))
            .orElse(Cursor.createGlobal(DOMAIN_CREATION_WATCHER, START_OF_TIME))
            .getCursorTime();
    DateTime searchEndTime = jpaTm().getTransactionTime();
    // SELECT fullyQualifiedDomain,
    List<AutoValue_DomainCreationWatcherAction_DomainNotification> domainsToNotify =
        jpaTm()
            .query(
                "SELECT NEW AutoValue_DomainCreationWatcherAction_DomainNotification("
                    + "fullyQualifiedDomainName, creationClientId, creationTime.timestamp, "
                    + "deletionTime) FROM Domain WHERE tld in :tlds AND creationTime >= :startTime "
                    + "AND creationTime < :endTime ORDER BY creationTime ASC",
                AutoValue_DomainCreationWatcherAction_DomainNotification.class)
            .setParameter("tlds", domainCreationWatcherTlds)
            .setParameter("startTime", CreateAutoTimestamp.create(searchStartTime))
            .setParameter("endTime", CreateAutoTimestamp.create(searchEndTime))
            .getResultList();
    sendEmail(domainsToNotify);
    jpaTm().put(Cursor.createGlobal(DOMAIN_CREATION_WATCHER, searchEndTime));
  }

  @VisibleForTesting
  void sendEmail(List<AutoValue_DomainCreationWatcherAction_DomainNotification> domainsToNotify) {
    StringBuilder bodyBuilder = new StringBuilder();
    domainsToNotify.forEach(d -> bodyBuilder.append(String.format("  - %s\n", d.toDisplayStr())));
    String body = bodyBuilder.toString();
    sendEmailService.sendEmail(emailMessage);
  }

  @AutoValue
  abstract static class DomainNotification {
    abstract String domainName();

    abstract String creationRegistrarId();

    abstract DateTime creationTime();

    abstract DateTime deletionTime();

    public String toDisplayStr() {
      return String.format(
          "%s (%s): %s%s",
          domainName(),
          creationRegistrarId(),
          creationTime(),
          deletionTime().isBefore(END_OF_TIME) ? String.format(" (del. %s)", deletionTime()) : "");
    }
  }
}
