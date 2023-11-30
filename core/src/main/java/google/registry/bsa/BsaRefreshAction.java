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

import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.bsa.persistence.DomainsRefresher;
import google.registry.bsa.persistence.RefreshSchedule;
import google.registry.bsa.persistence.RefreshScheduler;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import javax.inject.Inject;

@Action(
    service = Action.Service.BSA,
    path = BsaDownloadAction.PATH,
    method = POST,
    auth = Auth.AUTH_API_ADMIN)
public class BsaRefreshAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaDownload";

  private final RefreshScheduler scheduler;
  private final DomainsRefresher refresher;
  private final BsaLock bsaLock;
  private final Clock clock;
  private final Response response;

  @Inject
  BsaRefreshAction(
      RefreshScheduler scheduler,
      DomainsRefresher refresher,
      BsaLock bsaLock,
      Clock clock,
      Response response) {
    this.scheduler = scheduler;
    this.refresher = refresher;
    this.bsaLock = bsaLock;
    this.clock = clock;
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
    RefreshSchedule schedule = scheduler.schedule();
    switch (schedule.stage()) {
      case MAKE_DIFF:
        ImmutableList<UnblockableDomainChange> staleUnblockables =
            refresher.refreshStaleUnblockables();

        // Fall through
      case APPLY_DIFF:
        // Fall through
      case REPORT_REMOVALS:
        // Fall through
      case REPORT_ADDITIONS:
        break;
      case DONE:
        logger.atInfo().log("Unexpectedly reaching the `DONE` stage.");
        return null;
    }

    return null;
  }
}
