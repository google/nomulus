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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.model.domain.Domain;
import google.registry.model.domain.RegistryLock;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.tld.RegistryLockDao;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.tools.DomainLockUtils;
import google.registry.util.DateTimeUtils;
import google.registry.util.EmailMessage;
import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import org.joda.time.Duration;

/** Task that re-locks a previously-Registry-Locked domain after a predetermined period of time. */
@Action(
    service = Action.Service.BACKEND,
    path = RelockDomainAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public class RelockDomainAction implements Runnable {

  public static final String PATH = "/_dr/task/relockDomain";
  public static final String OLD_UNLOCK_REVISION_ID_PARAM = "oldUnlockRevisionId";
  public static final String PREVIOUS_ATTEMPTS_PARAM = "previousAttempts";

  static final int ATTEMPTS_BEFORE_SLOWDOWN = 36; // every ten minutes for six hours then every hour
  static final int FAILURES_BEFORE_EMAIL = 2; // email after three failures, one half hour
  private static final Duration TEN_MINUTES = Duration.standardMinutes(10);
  private static final Duration ONE_HOUR = Duration.standardHours(1);

  private static final String RELOCK_SUCCESS_EMAIL_TEMPLATE =
      """
      The domain %s was successfully re-locked.

      Please contact support at %s if you have any questions.\
      """;
  private static final String RELOCK_NON_RETRYABLE_FAILURE_EMAIL_TEMPLATE =
      """
      There was an error when automatically re-locking %s. Error message: %s

      Please contact support at %s if you have any questions.\
      """;
  private static final String RELOCK_TRANSIENT_FAILURE_EMAIL_TEMPLATE =
      "There was an unexpected error when automatically re-locking %s. We will continue retrying "
          + "the lock for five hours. Please contact support at %s if you have any questions";
  private static final String RELOCK_UNKNOWN_ID_FAILURE_EMAIL_TEMPLATE =
      "The old lock with revision ID %d is not present or is not accessible";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final long oldUnlockRevisionId;
  private final int previousAttempts;
  private final InternetAddress alertRecipientAddress;
  private final String supportEmail;
  private final GmailClient gmailClient;
  private final DomainLockUtils domainLockUtils;
  private final Response response;

  @Inject
  public RelockDomainAction(
      @Parameter(OLD_UNLOCK_REVISION_ID_PARAM) long oldUnlockRevisionId,
      @Parameter(PREVIOUS_ATTEMPTS_PARAM) int previousAttempts,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("supportEmail") String supportEmail,
      GmailClient gmailClient,
      DomainLockUtils domainLockUtils,
      Response response) {
    this.oldUnlockRevisionId = oldUnlockRevisionId;
    this.previousAttempts = previousAttempts;
    this.alertRecipientAddress = alertRecipientAddress;
    this.supportEmail = supportEmail;
    this.gmailClient = gmailClient;
    this.domainLockUtils = domainLockUtils;
    this.response = response;
  }

  @Override
  public void run() {
    /* We wish to manually control our retry behavior, in order to limit the number of retries
     * and/or notify registrars / support only after a certain number of retries, or only
     * with a certain type of failure. AppEngine will automatically retry on any non-2xx status
     * code, so return SC_NO_CONTENT (204) by default to avoid this auto-retry.
     *
     * See https://cloud.google.com/appengine/docs/standard/java/taskqueue/push/retrying-tasks
     * for more details on retry behavior. */
    response.setStatus(SC_NO_CONTENT);
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    tm().transact(this::relockDomain);
  }

  private void relockDomain() {
    RegistryLock oldLock = null;
    Domain domain;
    try {
      oldLock =
          RegistryLockDao.getByRevisionId(oldUnlockRevisionId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format("Unknown revision ID %d", oldUnlockRevisionId)));
      domain =
          tm().loadByKey(VKey.create(Domain.class, oldLock.getRepoId()))
              .cloneProjectedAtTime(tm().getTransactionTime());
    } catch (Throwable t) {
      handleTransientFailure(Optional.ofNullable(oldLock), t);
      return;
    }

    if (domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)
        || oldLock.getRelock() != null) {
      // The domain was manually locked, so we shouldn't worry about re-locking
      String message =
          String.format(
              "Domain %s is already manually re-locked, skipping automated re-lock.",
              domain.getDomainName());
      logger.atInfo().log(message);
      response.setPayload(message);
      return;
    }
    try {
      verifyDomainAndLockState(oldLock, domain);
    } catch (Throwable t) {
      // If the domain was, for example, transferred, then notify the old registrar and don't retry.
      handleNonRetryableFailure(oldLock, t);
      return;
    }
    try {
      applyRelock(oldLock);
    } catch (Throwable t) {
      handleTransientFailure(Optional.of(oldLock), t);
    }
  }

  private void applyRelock(RegistryLock oldLock) {
    domainLockUtils.administrativelyApplyLock(
        oldLock.getDomainName(),
        oldLock.getRegistrarId(),
        oldLock.getRegistryLockEmail(),
        oldLock.isSuperuser());
    logger.atInfo().log("Re-locked domain %s.", oldLock.getDomainName());
    response.setStatus(SC_OK);
    // Only send a success email if we previously sent a failure email
    if (previousAttempts > FAILURES_BEFORE_EMAIL) {
      sendSuccessEmail(oldLock);
    }
  }

  private void verifyDomainAndLockState(RegistryLock oldLock, Domain domain) {
    // Domain shouldn't be deleted or have a pending transfer/delete
    String domainName = domain.getDomainName();
    ImmutableSet<StatusValue> statusValues = domain.getStatusValues();
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_DELETE),
        "Domain %s has a pending delete.",
        domainName);
    checkArgument(
        !DateTimeUtils.isAtOrAfter(tm().getTransactionTime(), domain.getDeletionTime()),
        "Domain %s has been deleted.",
        domainName);
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_TRANSFER),
        "Domain %s has a pending transfer.",
        domainName);
    checkArgument(
        domain.getCurrentSponsorRegistrarId().equals(oldLock.getRegistrarId()),
        "Domain %s has been transferred from registrar %s to registrar %s since the unlock.",
        domainName,
        oldLock.getRegistrarId(),
        domain.getCurrentSponsorRegistrarId());
  }

  private void handleNonRetryableFailure(RegistryLock oldLock, Throwable t) {
    logger.atWarning().withCause(t).log(
        "Exception thrown when attempting to re-lock domain with old revision ID %d.",
        oldUnlockRevisionId);
    response.setPayload(String.format("Re-lock failed: %s", t.getMessage()));

    String body =
        String.format(
            RELOCK_NON_RETRYABLE_FAILURE_EMAIL_TEMPLATE,
            oldLock.getDomainName(),
            t.getMessage(),
            supportEmail);
    gmailClient.sendEmail(
        EmailMessage.newBuilder()
            .setBody(body)
            .setSubject(String.format("Error re-locking domain %s", oldLock.getDomainName()))
            .setRecipients(ImmutableSet.of(getEmailRecipient(oldLock)))
            .build());
  }

  private void handleTransientFailure(Optional<RegistryLock> oldLock, Throwable t) {
    String message = String.format("Re-lock failed: %s", t.getMessage());
    logger.atSevere().withCause(t).log(message);
    response.setPayload(message);

    if (previousAttempts == FAILURES_BEFORE_EMAIL) {
      if (oldLock.isPresent()) {
        sendGenericTransientFailureEmail(oldLock.get());
      } else {
        // if the old lock isn't present, something has gone horribly wrong
        sendUnknownRevisionIdAlertEmail();
      }
    }
    Duration timeBeforeRetry = previousAttempts < ATTEMPTS_BEFORE_SLOWDOWN ? TEN_MINUTES : ONE_HOUR;
    domainLockUtils.enqueueDomainRelock(timeBeforeRetry, oldUnlockRevisionId, previousAttempts + 1);
  }

  private void sendSuccessEmail(RegistryLock oldLock) {
    String body =
        String.format(RELOCK_SUCCESS_EMAIL_TEMPLATE, oldLock.getDomainName(), supportEmail);

    gmailClient.sendEmail(
        EmailMessage.newBuilder()
            .setBody(body)
            .setSubject(String.format("Successful re-lock of domain %s", oldLock.getDomainName()))
            .setRecipients(ImmutableSet.of(getEmailRecipient(oldLock)))
            .build());
  }

  private void sendGenericTransientFailureEmail(RegistryLock oldLock) {
    String body =
        String.format(
            RELOCK_TRANSIENT_FAILURE_EMAIL_TEMPLATE, oldLock.getDomainName(), supportEmail);
    // For an unexpected failure, notify both the lock-enabled contacts and our alerting email
    ImmutableSet<InternetAddress> allRecipients =
        new ImmutableSet.Builder<InternetAddress>()
            .add(getEmailRecipient(oldLock))
            .add(alertRecipientAddress)
            .build();
    gmailClient.sendEmail(
        EmailMessage.newBuilder()
            .setBody(body)
            .setSubject(String.format("Error re-locking domain %s", oldLock.getDomainName()))
            .setRecipients(allRecipients)
            .build());
  }

  private void sendUnknownRevisionIdAlertEmail() {
    gmailClient.sendEmail(
        EmailMessage.newBuilder()
            .setBody(String.format(RELOCK_UNKNOWN_ID_FAILURE_EMAIL_TEMPLATE, oldUnlockRevisionId))
            .setSubject("Error re-locking domain")
            .setRecipients(ImmutableSet.of(alertRecipientAddress))
            .build());
  }

  private InternetAddress getEmailRecipient(RegistryLock lock) {
    try {
      return new InternetAddress(lock.getRegistryLockEmail());
    } catch (AddressException e) {
      // this really shouldn't happen
      throw new RuntimeException(e);
    }
  }
}
