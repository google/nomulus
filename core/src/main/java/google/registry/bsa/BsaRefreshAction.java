// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static google.registry.bsa.BsaStringUtils.LINE_SPLITTER;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.bsa.api.BsaReportSender;
import google.registry.bsa.api.JsonSerializations;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.bsa.persistence.DomainsRefresher;
import google.registry.bsa.persistence.RefreshSchedule;
import google.registry.bsa.persistence.RefreshScheduler;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.BatchedStreams;
import google.registry.util.Clock;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.joda.time.Duration;

@Action(
    service = GaeService.BSA,
    path = BsaRefreshAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public class BsaRefreshAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaRefresh";

  private final RefreshScheduler scheduler;
  private final GcsClient gcsClient;
  private final BsaReportSender bsaReportSender;
  private final int transactionBatchSize;
  private final Duration domainCreateTxnCommitTimeLag;
  private final BsaEmailSender emailSender;
  private final BsaLock bsaLock;
  private final Clock clock;
  private final Response response;

  @Inject
  BsaRefreshAction(
      RefreshScheduler scheduler,
      GcsClient gcsClient,
      BsaReportSender bsaReportSender,
      @Config("bsaTxnBatchSize") int transactionBatchSize,
      @Config("domainCreateTxnCommitTimeLag") Duration domainCreateTxnCommitTimeLag,
      BsaEmailSender emailSender,
      BsaLock bsaLock,
      Clock clock,
      Response response) {
    this.scheduler = scheduler;
    this.gcsClient = gcsClient;
    this.bsaReportSender = bsaReportSender;
    this.transactionBatchSize = transactionBatchSize;
    this.domainCreateTxnCommitTimeLag = domainCreateTxnCommitTimeLag;
    this.emailSender = emailSender;
    this.bsaLock = bsaLock;
    this.clock = clock;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      if (!bsaLock.executeWithLock(this::runWithinLock)) {
        String message = "BSA refresh did not run: another BSA related task is running";
        logger.atInfo().log("%s.", message);
        emailSender.sendNotification(message, /* body= */ "");
      }
    } catch (Throwable throwable) {
      logger.atWarning().withCause(throwable).log("Failed to refresh BSA data.");
      emailSender.sendNotification("BSA refresh aborted", getStackTraceAsString(throwable));
    }
    // Always return OK. No need to use a retrier on `runWithinLock`. Its individual steps are
    // implicitly retried. If action fails, the next cron will continue at checkpoint.
    response.setStatus(SC_OK);
  }

  /** Executes the refresh action while holding the BSA lock. */
  Void runWithinLock() {
    // Cannot enroll new TLDs after download starts. This may change if b/309175410 is fixed.
    if (!Tlds.hasActiveBsaEnrollment(clock.nowUtc())) {
      logger.atInfo().log("No TLDs enrolled with BSA. Quitting.");
      return null;
    }
    Optional<RefreshSchedule> maybeSchedule = scheduler.schedule();
    if (maybeSchedule.isEmpty()) {
      logger.atInfo().log("No completed downloads yet. Exiting.");
      return null;
    }
    RefreshSchedule schedule = maybeSchedule.get();
    DomainsRefresher refresher =
        new DomainsRefresher(
            schedule.prevRefreshTime(),
            clock.nowUtc(),
            domainCreateTxnCommitTimeLag,
            transactionBatchSize);
    switch (schedule.stage()) {
      case CHECK_FOR_CHANGES:
        ImmutableList<UnblockableDomainChange> blockabilityChanges =
            refresher.checkForBlockabilityChanges();
        if (blockabilityChanges.isEmpty()) {
          logger.atInfo().log("No change to Unblockable domains found.");
          schedule.updateJobStage(RefreshStage.DONE);
          return null;
        }
        gcsClient.writeRefreshChanges(schedule.jobName(), blockabilityChanges.stream());
        schedule.updateJobStage(RefreshStage.APPLY_CHANGES);
        // Fall through
      case APPLY_CHANGES:
        try (Stream<UnblockableDomainChange> changes =
            gcsClient.readRefreshChanges(schedule.jobName())) {
          BatchedStreams.toBatches(changes, 500).forEach(refresher::applyUnblockableChanges);
        }
        schedule.updateJobStage(RefreshStage.UPLOAD_REMOVALS);
        // Fall through
      case UPLOAD_REMOVALS:
        try (Stream<UnblockableDomainChange> changes =
            gcsClient.readRefreshChanges(schedule.jobName())) {
          // Unblockables with changes in REASON are removed then added back. That is why they are
          // included in this stage.
          Optional<String> report =
              JsonSerializations.toUnblockableDomainsRemovalReport(
                  changes
                      .filter(UnblockableDomainChange::isChangeOrDelete)
                      .map(UnblockableDomainChange::domainName));
          if (report.isPresent()) {
            gcsClient.logRemovedUnblockableDomainsReport(
                schedule.jobName(), LINE_SPLITTER.splitToStream(report.get()));
            bsaReportSender.removeUnblockableDomainsUpdates(report.get());
          } else {
            logger.atInfo().log("No Unblockable domains to remove.");
          }
        }
        schedule.updateJobStage(RefreshStage.UPLOAD_ADDITIONS);
        // Fall through
      case UPLOAD_ADDITIONS:
        try (Stream<UnblockableDomainChange> changes =
            gcsClient.readRefreshChanges(schedule.jobName())) {
          Optional<String> report =
              JsonSerializations.toUnblockableDomainsReport(
                  changes
                      .filter(UnblockableDomainChange::isNewOrChange)
                      .map(UnblockableDomainChange::newValue));
          if (report.isPresent()) {
            gcsClient.logAddedUnblockableDomainsReport(
                schedule.jobName(), LINE_SPLITTER.splitToStream(report.get()));
            bsaReportSender.addUnblockableDomainsUpdates(report.get());
          } else {
            logger.atInfo().log("No new Unblockable domains to add.");
          }
        }
        schedule.updateJobStage(RefreshStage.DONE);
        break;
      case DONE:
        logger.atInfo().log("Unexpectedly reaching the `DONE` stage.");
        break;
    }
    return null;
  }
}
