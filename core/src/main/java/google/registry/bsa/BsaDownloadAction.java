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
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.persistence.DownloadSchedule;
import google.registry.bsa.persistence.DownloadScheduler;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.Optional;
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
  private final GcsClient gcsClient;
  private final BsaLock bsaLock;
  private final Response response;

  @Inject
  BsaDownloadAction(
      DownloadScheduler downloadScheduler,
      BlockListFetcher blockListFetcher,
      GcsClient gcsClient,
      BsaLock bsaLock,
      Response response) {
    this.downloadScheduler = downloadScheduler;
    this.blockListFetcher = blockListFetcher;
    this.gcsClient = gcsClient;
    this.bsaLock = bsaLock;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      if (!bsaLock.executeWithLock(this::runWithinLock)) {
        logger.atInfo().log("Job is being executed by another worker.");
      }
    } catch (Throwable throwable) {
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
    DownloadSchedule schedule = scheduleOptional.get();
    switch (schedule.stage()) {
      case DOWNLOAD:
        try (LazyBlockList block = blockListFetcher.fetch(BLOCK);
            LazyBlockList blockPlus = blockListFetcher.fetch(BLOCK_PLUS)) {
          ImmutableMap<BlockList, String> fetchedChecksums =
              ImmutableMap.of(BLOCK, block.peekChecksum(), BLOCK_PLUS, blockPlus.peekChecksum());
          ImmutableMap<BlockList, String> prevChecksums =
              schedule
                  .latestCompleted()
                  .map(DownloadSchedule.CompletedJob::checksums)
                  .orElseGet(ImmutableMap::of);
          // TODO(11/30/2023): uncomment below block when BSA checksums are available.
          // if (!schedule.forceDownload() && Objects.equals(fetchedChecksums, prevChecksums)) {
          //   schedule.updateJobStage(DownloadStage.NOP, fetchedChecksums);
          //   return null;
          // }
          ImmutableMap<BlockList, String> actualChecksum =
              gcsClient.saveAndChecksumBlockList(
                  schedule.jobName(), ImmutableList.of(block, blockPlus));
          // TODO(11/30/2023): uncomment below block when BSA checksums are available.
          // if (!Objects.equals(fetchedChecksums, actualChecksum)) {
          //   logger.atSevere().log(
          //       "Mismatching checksums: BSA's is [%s], ours is [%s]",
          //       fetchedChecksums, actualChecksum);
          //   schedule.updateJobStage(DownloadStage.CHECKSUMS_NOT_MATCH);
          //   return null;
          // }
          schedule.updateJobStage(DownloadStage.MAKE_DIFF, actualChecksum);
        }
        // Fall through
      case MAKE_DIFF:
        // TODO(11/30/2023): fill out the rest stages.
      case APPLY_DIFF:
      case START_UPLOADING:
      case UPLOAD_DOMAINS_IN_USE:
      case FINISH_UPLOADING:
      case DONE:
      case NOP:
      case CHECKSUMS_NOT_MATCH:
        logger.atWarning().log("Unexpectedly reached the %s stage.", schedule.stage());
        break;
    }
    return null;
  }
}
