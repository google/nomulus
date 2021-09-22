// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import com.google.appengine.api.log.LogQuery;
import com.google.appengine.api.log.LogService;
import com.google.appengine.api.log.LogServiceFactory;
import com.google.appengine.api.log.RequestLogs;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import java.util.Collections;
import javax.inject.Inject;

/** Implementation of the {@link RequestStatusChecker} interface. */
public class RequestStatusCheckerImpl implements RequestStatusChecker {

  private static final long serialVersionUID = -8161977032130865437L;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting
  static LogService logService = LogServiceFactory.getLogService();

  /**
   * The key to {@link Environment#getAttributes}'s request_log_id value.
   */
  private static final String REQUEST_LOG_ID_KEY = "com.google.appengine.runtime.request_log_id";

  @Inject public RequestStatusCheckerImpl() {}

  /**
   * Returns the unique log identifier of the current request.
   *
   * <p>May be safely called multiple times, will always return the same result (within the same
   * request).
   *
   * @see <a href="https://cloud.google.com/appengine/docs/standard/java/how-requests-are-handled#request-ids">appengine documentation</a>
   */
  @Override
  public String getLogId() {
    String requestLogId =
        ApiProxy.getCurrentEnvironment().getAttributes().get(REQUEST_LOG_ID_KEY).toString();
    logger.atInfo().log("Current requestLogId: %s.", requestLogId);
    // We want to make sure there actually is a log to query for this request, even if the request
    // dies right after this call.
    //
    // flushLogs() is synchronous, so once the function returns, no matter what happens next, the
    // returned requestLogId will point to existing logs.
    ApiProxy.flushLogs();
    return requestLogId;
  }

  /**
   * Returns true if the given request is currently running.
   *
   * @see <a href="https://cloud.google.com/appengine/docs/standard/java/javadoc/com/google/appengine/api/log/LogQuery">appengine documentation</a>
   */
  @Override
  public boolean isRunning(String requestLogId) {
    RequestLogs requestLogs =
        Iterables.getOnlyElement(
            logService.fetch(
                LogQuery.Builder
                    .withRequestIds(Collections.singletonList(requestLogId))
                    .includeAppLogs(false)
                    .includeIncomplete(true)),
            null);
    // requestLogs will be null if that requestLogId isn't found at all, which can happen if the
    // request is too new (it can take several seconds until the logs are available for "fetch").
    // So we have to assume it's "running" in that case.
    if (requestLogs == null) {
      logger.atInfo().log(
          "Queried an unrecognized requestLogId %s - assume it's running.", requestLogId);
      return true;
    }
    logger.atInfo().log(
        "Found logs for requestLogId %s - isFinished: %b", requestLogId, requestLogs.isFinished());
    return !requestLogs.isFinished();
  }
}
