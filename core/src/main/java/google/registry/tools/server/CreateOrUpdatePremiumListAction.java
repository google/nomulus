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

package google.registry.tools.server;

import static com.google.common.flogger.LazyArgs.lazy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.request.JsonResponse;
import google.registry.request.Parameter;
import javax.inject.Inject;

/** Abstract base class for actions that update premium lists. */
public abstract class CreateOrUpdatePremiumListAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int MAX_LOGGING_PREMIUM_LIST_LENGTH = 1000;

  public static final String NAME_PARAM = "name";
  public static final String INPUT_PARAM = "inputData";

  @Inject JsonResponse response;

  @Inject
  @Parameter("premiumListName")
  String name;

  @Inject
  @Parameter(INPUT_PARAM)
  String inputData;

  @Override
  public void run() {
    try {
      checkArgumentNotNull(inputData, "Input data must not be null");
      save();
    } catch (IllegalArgumentException e) {
      logger.atInfo().withCause(e).log(
          "Usage error in attempting to save premium list from nomulus tool command");
      response.setPayload(ImmutableMap.of("error", e.getMessage(), "status", "error"));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error saving premium list to Datastore from nomulus tool command");
      response.setPayload(ImmutableMap.of("error", e.getMessage(), "status", "error"));
    }
  }

  /** Logs the premium list data at INFO, truncated if too long. */
  void logInputData() {
    String logData = (inputData == null) ? "(null)" : inputData;
    logger.atInfo().log(
        "Received the following input data: %s",
        lazy(
            () ->
                (logData.length() < MAX_LOGGING_PREMIUM_LIST_LENGTH)
                    ? logData
                    : (logData.substring(0, MAX_LOGGING_PREMIUM_LIST_LENGTH) + "<truncated>")));
  }

  /** Saves the premium list to both Datastore and Cloud SQL. */
  protected abstract void save();
}
