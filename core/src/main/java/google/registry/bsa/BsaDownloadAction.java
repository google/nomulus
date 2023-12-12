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

package google.registry.bsa;

import static google.registry.bsa.BlockList.BLOCK;
import static google.registry.bsa.BlockList.BLOCK_PLUS;
import static google.registry.bsa.api.JsonSerializations.toCompletedOrdersReport;
import static google.registry.bsa.api.JsonSerializations.toInProgressOrdersReport;
import static google.registry.bsa.api.JsonSerializations.toUnblockableDomainsReport;
import static google.registry.bsa.persistence.LabelDiffUpdates.applyLabelDiff;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.BatchedStreams.batch;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import dagger.Lazy;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.BsaDiffCreator.BsaDiff;
import google.registry.bsa.api.BsaReportSender;
import google.registry.bsa.api.Label;
import google.registry.bsa.api.Order;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.persistence.DownloadSchedule;
import google.registry.bsa.persistence.DownloadScheduler;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;

@Action(
    service = Action.Service.BSA,
    path = BsaDownloadAction.PATH,
    method = POST,
    auth = Auth.AUTH_API_ADMIN)
public class BsaDownloadAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaDownload";

  private final DownloadScheduler downloadScheduler;
  private final BlockListFetcher blockListFetcher;
  private final BsaDiffCreator diffCreator;
  private final BsaReportSender bsaReportSender;
  private final GcsClient gcsClient;
  private final Lazy<IdnChecker> lazyIdnChecker;
  private final BsaLock bsaLock;
  private final Clock clock;
  private final int transactionBatchSize;
  private final Response response;

  @Inject
  BsaDownloadAction(
      DownloadScheduler downloadScheduler,
      BlockListFetcher blockListFetcher,
      BsaDiffCreator diffCreator,
      BsaReportSender bsaReportSender,
      GcsClient gcsClient,
      Lazy<IdnChecker> lazyIdnChecker,
      BsaLock bsaLock,
      Clock clock,
      @Config("bsaTxnBatchSize") int transactionBatchSize,
      Response response) {
    this.downloadScheduler = downloadScheduler;
    this.blockListFetcher = blockListFetcher;
    this.diffCreator = diffCreator;
    this.bsaReportSender = bsaReportSender;
    this.gcsClient = gcsClient;
    this.lazyIdnChecker = lazyIdnChecker;
    this.bsaLock = bsaLock;
    this.clock = clock;
    this.transactionBatchSize = transactionBatchSize;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      if (!bsaLock.executeWithLock(this::runWithinLock)) {
        logger.atInfo().log("Job is being executed by another worker.");
      }
    } catch (Throwable throwable) {
      // TODO(12/31/2023): consider sending an alert email.
      logger.atWarning().withCause(throwable).log("Failed to update block lists.");
    }
    // Always return OK. Let the next cron job retry.
    response.setStatus(SC_OK);
  }

  Void runWithinLock() {
    Optional<DownloadSchedule> scheduleOptional = downloadScheduler.schedule();
    if (!scheduleOptional.isPresent()) {
      logger.atInfo().log("Nothing to do.");
      return null;
    }
    BsaDiff diff = null;
    DownloadSchedule schedule = scheduleOptional.get();
    switch (schedule.stage()) {
      case DOWNLOAD_BLOCK_LISTS:
        try (LazyBlockList block = blockListFetcher.fetch(BLOCK);
            LazyBlockList blockPlus = blockListFetcher.fetch(BLOCK_PLUS)) {
          ImmutableMap<BlockList, String> fetchedChecksums =
              ImmutableMap.of(BLOCK, block.checksum(), BLOCK_PLUS, blockPlus.checksum());
          ImmutableMap<BlockList, String> prevChecksums =
              schedule
                  .latestCompleted()
                  .map(DownloadSchedule.CompletedJob::checksums)
                  .orElseGet(ImmutableMap::of);
          boolean checksumsMatch = Objects.equals(fetchedChecksums, prevChecksums);
          if (!schedule.alwaysDownload() && checksumsMatch) {
            logger.atInfo().log(
                "Skipping download b/c block list checksums have not changed: [%s]",
                fetchedChecksums);
            schedule.updateJobStage(DownloadStage.NOP, fetchedChecksums);
            return null;
          } else if (checksumsMatch) {
            logger.atInfo().log(
                "Checksums match but download anyway: elapsed time since last download exceeds"
                    + " configured limit.");
          }
          ImmutableMap<BlockList, String> actualChecksum =
              gcsClient.saveAndChecksumBlockList(
                  schedule.jobName(), ImmutableList.of(block, blockPlus));
          if (!Objects.equals(fetchedChecksums, actualChecksum)) {
            logger.atSevere().log(
                "Inlined checksums do not match those calculated by us. Theirs: [%s]; ours: [%s]",
                fetchedChecksums, actualChecksum);
            schedule.updateJobStage(DownloadStage.CHECKSUMS_DO_NOT_MATCH);
            // TODO(01/15/24): add email alert.
            return null;
          }
          schedule.updateJobStage(DownloadStage.MAKE_ORDER_AND_LABEL_DIFF, actualChecksum);
        }
        // Fall through
      case MAKE_ORDER_AND_LABEL_DIFF:
        diff = diffCreator.createDiff(schedule, lazyIdnChecker.get());
        gcsClient.writeOrderDiffs(schedule.jobName(), diff.getOrders());
        gcsClient.writeLabelDiffs(schedule.jobName(), diff.getLabels());
        schedule.updateJobStage(DownloadStage.APPLY_ORDER_AND_LABEL_DIFF);
        // Fall through
      case APPLY_ORDER_AND_LABEL_DIFF:
        try (Stream<Label> labels =
            diff != null ? diff.getLabels() : gcsClient.readLabelDiffs(schedule.jobName())) {
          Stream<ImmutableList<Label>> batches = batch(labels, transactionBatchSize);
          gcsClient.writeUnblockableDomains(
              schedule.jobName(),
              batches
                  .map(
                      batch ->
                          applyLabelDiff(batch, lazyIdnChecker.get(), schedule, clock.nowUtc()))
                  .flatMap(ImmutableList::stream));
        }
        schedule.updateJobStage(DownloadStage.REPORT_START_OF_ORDER_PROCESSING);
        // Fall through
      case REPORT_START_OF_ORDER_PROCESSING:
        try (Stream<Order> orders = gcsClient.readOrderDiffs(schedule.jobName())) {
          // We expect that all order instances and the json string can fit in memory.
          Optional<String> report = toInProgressOrdersReport(orders);
          if (report.isPresent()) {
            // Log report data
            gcsClient.logInProgressOrderReport(
                schedule.jobName(), BsaStringUtils.LINE_SPLITTER.splitToStream(report.get()));
            bsaReportSender.sendOrderStatusReport(report.get());
          } else {
            logger.atInfo().log("No new or deleted orders in this round.");
          }
        }
        schedule.updateJobStage(DownloadStage.UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS);
        // Fall through
      case UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS:
        try (Stream<UnblockableDomain> unblockables =
            gcsClient.readUnblockableDomains(schedule.jobName())) {
          /* The number of unblockable domains may be huge in theory (label x ~50 tlds), but in
           *  practice should be relatively small (tens of thousands?). Batches can be introduced
           * if size becomes a problem.
           */
          Optional<String> report = toUnblockableDomainsReport(unblockables);
          if (report.isPresent()) {
            gcsClient.logAddedUnblockableDomainsReport(
                schedule.jobName(), BsaStringUtils.LINE_SPLITTER.splitToStream(report.get()));
            // During downloads, unblockable domains are only added, not removed.
            bsaReportSender.addUnblockableDomainsUpdates(report.get());
          } else {
            logger.atInfo().log("No changes in the set of unblockable domains in this round.");
          }
        }
        schedule.updateJobStage(DownloadStage.REPORT_END_OF_ORDER_PROCESSING);
        // Fall through
      case REPORT_END_OF_ORDER_PROCESSING:
        try (Stream<Order> orders = gcsClient.readOrderDiffs(schedule.jobName())) {
          // Orders are expected to be few, so the report can be kept in memory.
          Optional<String> report = toCompletedOrdersReport(orders);
          if (report.isPresent()) {
            gcsClient.logCompletedOrderReport(
                schedule.jobName(), BsaStringUtils.LINE_SPLITTER.splitToStream(report.get()));
            bsaReportSender.sendOrderStatusReport(report.get());
          }
        }
        schedule.updateJobStage(DownloadStage.DONE);
        return null;
      case DONE:
      case NOP:
      case CHECKSUMS_DO_NOT_MATCH:
        logger.atWarning().log("Unexpectedly reached the %s stage.", schedule.stage());
        break;
    }
    return null;
  }
}
