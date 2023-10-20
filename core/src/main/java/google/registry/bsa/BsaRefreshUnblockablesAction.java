package google.registry.bsa;

import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import google.registry.request.Response;
import javax.inject.Inject;

public class BsaRefreshUnblockablesAction implements Runnable {

  private FluentLogger logger = FluentLogger.forEnclosingClass();
  private final BsaLock bsaLock;
  private final Response response;

  @Inject
  BsaRefreshUnblockablesAction(BsaLock bsaLock, Response response) {
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
    // Always return OK. Let the next cron job retry failures.
    response.setStatus(SC_OK);
  }

  Void runWithinLock() {

    refreshStaleUnblockables();
    discoverNewReservedNames("");
    return null;
  }

  private void refreshStaleUnblockables() {
    // for each unblockable names in the DB table
    //    if (reason is `REGISTERED` and domain is deleted) or
    //         if (reason is `RESERVED` and domain is no longer reserved)
    //           recompute blockability.
    //           do one of two things:
    //               - Remove from table and report to BSA as a remove
    //               - Change reason (registered <--> reserved), which can be implemented as
    //                 remove + add.
    //                 Make sure add happens after remove. Ask BSA:
  }

  private void discoverNewReservedNames(String reservedListName) {
    // For each tld that uses this list
    //   for each name on the list
    //     if name.tld is not in the unblockedDomain table,
    //        add it to the table
    //        report it to BSA
  }
}
