package google.registry.bsa;

import static google.registry.bsa.common.BlockList.BLOCK;
import static google.registry.bsa.common.BlockList.BLOCK_PLUS;
import static google.registry.bsa.persistence.LabelUpdates.applyLabelDiff;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.bsa.BlockLabelsDiffCreator.BlockLabelsDiff;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.common.DownloadJobStage;
import google.registry.bsa.common.Label;
import google.registry.bsa.common.Order;
import google.registry.bsa.common.UnblockableDomain;
import google.registry.bsa.jobs.BsaJob;
import google.registry.bsa.jobs.BsaJobScheduler;
import google.registry.bsa.persistence.BsaDownload;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.MoreStreams;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.inject.Inject;

@Action(
    service = Action.Service.BACKEND,
    path = BsaDownloadAction.PATH,
    method = POST,
    auth = Auth.AUTH_API_ADMIN)
public class BsaDownloadAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaDownload";

  private final BsaJobScheduler jobScheduler;
  private final BsaBulkApiClient bsaBulkApiClient;
  private final GcsClient gcsClient;
  private final BlockListFetcher blockListFetcher;
  private final BlockLabelsDiffCreator diffCreator;
  private final BsaLock bsaLlock;

  private final Clock clock;
  private final Response response;

  @Inject
  BsaDownloadAction(
      BsaJobScheduler jobScheduler,
      BsaBulkApiClient bsaBulkApiClient,
      GcsClient gcsClient,
      BlockListFetcher blockListFetcher,
      BlockLabelsDiffCreator diffCreator,
      BsaLock locker,
      Clock clock,
      Response response) {
    this.jobScheduler = jobScheduler;
    this.bsaBulkApiClient = bsaBulkApiClient;
    this.gcsClient = gcsClient;
    this.blockListFetcher = blockListFetcher;
    this.diffCreator = diffCreator;
    this.bsaLlock = locker;
    this.clock = clock;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      if (!bsaLlock.executeWithLock(this::runWithinLock)) {
        logger.atInfo().log("Job is being executed by another worker.");
      }
    } catch (Throwable throwable) {
      logger.atWarning().withCause(throwable).log("Failed to update block lists.");
    }
    // Always return OK. Let the next cron job retry failures.
    response.setStatus(SC_OK);
  }

  Void runWithinLock() {
    Optional<BsaJob> jobOptional = jobScheduler.getJob();
    if (!jobOptional.isPresent()) {
      logger.atInfo().log("Not scheduled.");
      return null;
    }
    /**
     * Important: make sure the following cannot proceed if there is a job in progress (not in DONE
     * or NOP stage) :
     *
     * <ul>
     *   <li>The action that checks for newly-blockable domain names.
     *   <li>Command that enrolls a TLD with BSA
     *   <li>Command that changes the set of IDNs supported by a TLD.
     * </ul>
     */
    BsaJob jobInfo = jobOptional.get();
    Supplier<IdnChecker> idnCheckerSupplier = Suppliers.memoize(IdnChecker::new);
    switch (jobInfo.job().getNextStage()) {
      case DOWNLOAD:
        try (LazyBlockList block = blockListFetcher.fetch(BLOCK);
            LazyBlockList blockPlus = blockListFetcher.fetch(BLOCK_PLUS)) {
          ImmutableMap<BlockList, String> fetchedChecksums =
              ImmutableMap.of(BLOCK, block.peekChecksum(), BLOCK_PLUS, blockPlus.peekChecksum());
          ImmutableMap<BlockList, String> prevChecksums =
              jobInfo.previousJob().map(BsaDownload::getChecksums).orElseGet(ImmutableMap::of);
          if (!jobInfo.forceUpdate() && Objects.equals(fetchedChecksums, prevChecksums)) {
            jobInfo.updateJobStageToNop(fetchedChecksums);
            return null;
          }
          ImmutableMap<BlockList, String> actualChecksum =
              gcsClient.saveAndChecksumBlockList(
                  jobInfo.job().getJobId(), ImmutableList.of(block, blockPlus));
          if (!Objects.equals(fetchedChecksums, actualChecksum)) {
            logger.atSevere().log(
                "Mismatching checksums: BSA's is [%s], ours is [%s]",
                fetchedChecksums, actualChecksum);
            jobInfo.updateJobStage(DownloadJobStage.CHECKSUMS_NOT_MATCH);
            return null;
          }
          jobInfo.updateJobStage(DownloadJobStage.MAKE_DIFF);
        }
        // Fall through
      case MAKE_DIFF:
        BlockLabelsDiff diff = diffCreator.createDiff(jobInfo, idnCheckerSupplier.get());
        gcsClient.writeOrderDiffs(jobInfo.job().getJobId(), diff.getOrders());
        gcsClient.writeLabelDiffs(jobInfo.job().getJobId(), diff.getLabels());
        jobInfo.updateJobStage(DownloadJobStage.APPLY_DIFF);
        // Fall through
      case APPLY_DIFF:
        // TODO: reuse result from previous step if possible
        try (Stream<Label> labels = gcsClient.readLabelDiffs(jobInfo.job().getJobId())) {
          Stream<ImmutableList<Label>> batches = MoreStreams.batchedStream(labels, 500);
          gcsClient.writeUnblockableDomains(
              jobInfo.job().getJobId(),
              batches
                  .map(
                      batch ->
                          applyLabelDiff(batch, idnCheckerSupplier.get(), jobInfo, clock.nowUtc()))
                  .flatMap(ImmutableList::stream));
        }
        jobInfo.updateJobStage(DownloadJobStage.START_UPLOADING);
        // Fall through
      case START_UPLOADING:
        try (Stream<Order> orders = gcsClient.readOrderDiffs(jobInfo.job().getJobId())) {
          bsaBulkApiClient.startProcessingOrders(orders);
        }
        jobInfo.updateJobStage(DownloadJobStage.UPLOAD_UNBLOCKABLE_DOMAINS);
        // Fall through
      case UPLOAD_UNBLOCKABLE_DOMAINS:
        try (Stream<UnblockableDomain> unblockables =
            gcsClient.readUnblockableDomains(jobInfo.job().getJobId())) {}

        jobInfo.updateJobStage(DownloadJobStage.FINISH_UPLOADING);
        // Fall through
      case FINISH_UPLOADING:
        try (Stream<Order> orders = gcsClient.readOrderDiffs(jobInfo.job().getJobId())) {
          bsaBulkApiClient.completeProcessingOrders(orders);
        }
        jobInfo.updateJobStage(DownloadJobStage.DONE);
        return null;
      case DONE:
      case NOP:
      case CHECKSUMS_NOT_MATCH:
        logger.atWarning().log("Unexpectedly reached the %s stage.", jobInfo.job().getNextStage());
        break;
    }
    return null;
  }
}
