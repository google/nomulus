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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.request.Action.Method.POST;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.tools.DomainLockUtils;
import google.registry.util.DateTimeUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * Task that relocks a previously-Registry-Locked domain after some predetermined period of time.
 */
@Action(
    service = Action.Service.BACKEND,
    path = RelockDomainAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RelockDomainAction implements Runnable {

  public static final String PATH = "/_dr/task/relockDomain";
  public static final String OLD_UNLOCK_REVISION_ID_PARAM = "oldUnlockRevisionId";

  private static final String RELOCK_SUCCESS_EMAIL_TEMPLATE =
      "The domain %s was successfully re-locked.\n\nPlease contact support at %s if you have any "
          + "questions.";
  private static final String RELOCK_FAILURE_EMAIL_TEMPLATE =
      "There was an error when automatically re-locking %s. Error message: %s\n\nPlease contact "
          + "support at %s if you have any questions.";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final long oldUnlockRevisionId;
  private final InternetAddress alertRecipientAddress;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final String supportEmail;
  private final SendEmailService sendEmailService;
  private final DomainLockUtils domainLockUtils;
  private final Response response;

  @Inject
  public RelockDomainAction(
      @Parameter(OLD_UNLOCK_REVISION_ID_PARAM) long oldUnlockRevisionId,
      @Config("alertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      @Config("supportEmail") String supportEmail,
      SendEmailService sendEmailService,
      DomainLockUtils domainLockUtils,
      Response response) {
    this.oldUnlockRevisionId = oldUnlockRevisionId;
    this.alertRecipientAddress = alertRecipientAddress;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.supportEmail = supportEmail;
    this.sendEmailService = sendEmailService;
    this.domainLockUtils = domainLockUtils;
    this.response = response;
  }

  @Override
  public void run() {
    jpaTm().transact(this::relockDomain);
  }

  private void relockDomain() {
    RegistryLock oldLock = null;
    try {
      oldLock =
          RegistryLockDao.getByRevisionId(oldUnlockRevisionId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format("Unknown revision ID %d", oldUnlockRevisionId)));
      DomainBase domain =
          ofy()
              .load()
              .type(DomainBase.class)
              .id(oldLock.getRepoId())
              .now()
              .cloneProjectedAtTime(jpaTm().getTransactionTime());

      if (domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)
          || oldLock.getRelock() != null) {
        // The domain was manually locked, so we shouldn't worry about relocking
        String message =
            String.format(
                "Domain %s is already manually relocked, skipping automated relock.",
                domain.getDomainName());
        logger.atInfo().log(message);
        // SC_NO_CONTENT (204) skips retry -- see the comment below
        response.setStatus(SC_NO_CONTENT);
        response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
        response.setPayload(message);
        return;
      }
      verifyDomainAndLockState(oldLock, domain);
    } catch (Throwable t) {
      /* If there's a bad verification code or the domain is in a bad state, we won't want to retry.
       * AppEngine will retry on non-2xx error codes, so we return SC_NO_CONTENT (204) to avoid it.
       *
       * See https://cloud.google.com/appengine/docs/standard/java/taskqueue/push/retrying-tasks
       * for more details on retry behavior. */
      logger.atWarning().withCause(t).log(
          "Exception when attempting to relock domain with old revision ID %d.",
          oldUnlockRevisionId);
      response.setStatus(SC_NO_CONTENT);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", t.getMessage()));
      if (oldLock != null) {
        sendFailureAlertEmail(oldLock, t);
      }
      return;
    }
    applyRelock(oldLock);
  }

  private void applyRelock(RegistryLock oldLock) {
    try {
      domainLockUtils.administrativelyApplyLock(
          oldLock.getDomainName(),
          oldLock.getRegistrarId(),
          oldLock.getRegistrarPocId(),
          oldLock.isSuperuser());
      logger.atInfo().log("Relocked domain %s.", oldLock.getDomainName());
      response.setStatus(SC_OK);
      sendSuccessEmail(oldLock);
    } catch (Throwable t) {
      // Any errors that occur here are unexpected, so we should retry. Return a non-2xx
      // error code to get AppEngine to retry
      logger.atSevere().withCause(t).log(
          "Exception when attempting to relock domain %s.", oldLock.getDomainName());
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", t.getMessage()));
      sendFailureAlertEmail(oldLock, t);
    }
  }

  private void verifyDomainAndLockState(RegistryLock oldLock, DomainBase domain) {
    // Domain shouldn't be deleted or have a pending transfer/delete
    String domainName = domain.getDomainName();
    checkArgument(
        !DateTimeUtils.isAtOrAfter(jpaTm().getTransactionTime(), domain.getDeletionTime()),
        "Domain %s has been deleted.",
        domainName);
    ImmutableSet<StatusValue> statusValues = domain.getStatusValues();
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_DELETE),
        "Domain %s has a pending delete.",
        domainName);
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_TRANSFER),
        "Domain %s has a pending transfer.",
        domainName);
    checkArgument(
        domain.getCurrentSponsorClientId().equals(oldLock.getRegistrarId()),
        "Domain %s has been transferred from registrar %s to registrar %s since the unlock.",
        domainName,
        oldLock.getRegistrarId(),
        domain.getCurrentSponsorClientId());
  }

  private void sendSuccessEmail(RegistryLock oldLock) {
    String body =
        String.format(RELOCK_SUCCESS_EMAIL_TEMPLATE, oldLock.getDomainName(), supportEmail);

    sendEmailService.sendEmail(
        EmailMessage.newBuilder()
            .setFrom(gSuiteOutgoingEmailAddress)
            .setBody(body)
            .setSubject(String.format("Successful re-lock of domain %s", oldLock.getDomainName()))
            .setRecipients(getEmailRecipients(oldLock.getRegistrarId()))
            .build());
  }

  private void sendFailureAlertEmail(RegistryLock oldLock, Throwable cause) {
    String body =
        String.format(
            RELOCK_FAILURE_EMAIL_TEMPLATE,
            oldLock.getDomainName(),
            cause.getMessage(),
            supportEmail);

    // If there's a failure, notify both the registry-lock enabled contacts and our alerting email
    ImmutableSet<InternetAddress> allRecipients =
        new ImmutableSet.Builder<InternetAddress>()
            .addAll(getEmailRecipients(oldLock.getRegistrarId()))
            .add(alertRecipientAddress)
            .build();
    sendEmailService.sendEmail(
        EmailMessage.newBuilder()
            .setFrom(gSuiteOutgoingEmailAddress)
            .setBody(body)
            .setSubject(String.format("Error re-locking domain %s", oldLock.getDomainName()))
            .setRecipients(allRecipients)
            .build());
  }

  // Notify all registry-lock enabled contacts of success or failure
  private ImmutableSet<InternetAddress> getEmailRecipients(String registrarId) {
    Registrar registrar =
        Registrar.loadByClientIdCached(registrarId)
            .orElseThrow(
                () ->
                    new IllegalStateException(String.format("Unknown registrar %s", registrarId)));

    ImmutableSet<String> registryLockEmailAddresses =
        registrar.getContacts().stream()
            .filter(RegistrarContact::isRegistryLockAllowed)
            .map(RegistrarContact::getRegistryLockEmailAddress)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(toImmutableSet());

    ImmutableSet.Builder<InternetAddress> builder = new ImmutableSet.Builder<>();
    // can't use streams due to the 'throws' in the InternetAddress constructor
    for (String registryLockEmailAddress : registryLockEmailAddresses) {
      try {
        builder.add(new InternetAddress(registryLockEmailAddress));
      } catch (AddressException e) {
        // This shouldn't stop any other emails going out, so swallow it
        logger.atWarning().log("Invalid email address %s", registryLockEmailAddress);
      }
    }
    return builder.build();
  }
}
