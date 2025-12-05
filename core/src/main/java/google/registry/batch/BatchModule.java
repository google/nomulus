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

package google.registry.batch;

import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractIntParameter;
import static google.registry.request.RequestParameters.extractLongParameter;
import static google.registry.request.RequestParameters.extractOptionalBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredDatetimeParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfDatetimeParameters;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import dagger.Module;
import dagger.Provides;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.OptionalJsonPayload;
import google.registry.request.Parameter;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;

/** Dagger module for injecting common settings for batch actions. */
@Module
public class BatchModule {

  public static final String PARAM_FAST = "fast";

  static final int DEFAULT_MAX_QPS = 10;

  @Provides
  @Parameter("url")
  static String provideUrl(HttpServletRequest req) {
    return extractRequiredParameter(req, "url");
  }

  @Provides
  @Parameter("jobName")
  static Optional<String> provideJobName(HttpServletRequest req) {
    return extractOptionalParameter(req, "jobName");
  }

  @Provides
  @Parameter("jobId")
  static Optional<String> provideJobId(HttpServletRequest req) {
    return extractOptionalParameter(req, "jobId");
  }

  @Provides
  @Parameter("numJobsToDelete")
  static Optional<Integer> provideNumJobsToDelete(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "numJobsToDelete");
  }

  @Provides
  @Parameter("daysOld")
  static Optional<Integer> provideDaysOld(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "daysOld");
  }

  @Provides
  @Parameter("force")
  static Optional<Boolean> provideForce(HttpServletRequest req) {
    return extractOptionalBooleanParameter(req, "force");
  }

  @Provides
  @Parameter(PARAM_RESOURCE_KEY)
  static String provideResourceKey(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_RESOURCE_KEY);
  }

  @Provides
  @Parameter(PARAM_REQUESTED_TIME)
  static DateTime provideRequestedTime(HttpServletRequest req) {
    return extractRequiredDatetimeParameter(req, PARAM_REQUESTED_TIME);
  }

  @Provides
  @Parameter(PARAM_RESAVE_TIMES)
  static ImmutableSet<DateTime> provideResaveTimes(HttpServletRequest req) {
    return extractSetOfDatetimeParameters(req, PARAM_RESAVE_TIMES);
  }

  @Provides
  @Parameter(RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM)
  static long provideOldUnlockRevisionId(HttpServletRequest req) {
    return extractLongParameter(req, RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM);
  }

  @Provides
  @Parameter(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM)
  static int providePreviousAttempts(HttpServletRequest req) {
    return extractIntParameter(req, RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM);
  }

  @Provides
  @Parameter(ExpandBillingRecurrencesAction.PARAM_START_TIME)
  static Optional<DateTime> provideStartTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(req, ExpandBillingRecurrencesAction.PARAM_START_TIME);
  }

  @Provides
  @Parameter(ExpandBillingRecurrencesAction.PARAM_END_TIME)
  static Optional<DateTime> provideEndTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(req, ExpandBillingRecurrencesAction.PARAM_END_TIME);
  }

  @Provides
  @Parameter(WipeOutContactHistoryPiiAction.PARAM_CUTOFF_TIME)
  static Optional<DateTime> provideCutoffTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(req, WipeOutContactHistoryPiiAction.PARAM_CUTOFF_TIME);
  }

  @Provides
  @Parameter(ExpandBillingRecurrencesAction.PARAM_ADVANCE_CURSOR)
  static boolean provideAdvanceCursor(HttpServletRequest req) {
    return extractBooleanParameter(req, ExpandBillingRecurrencesAction.PARAM_ADVANCE_CURSOR);
  }

  @Provides
  @Parameter(PARAM_FAST)
  static boolean provideIsFast(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_FAST);
  }

  @Provides
  @Parameter("maxQps")
  static int provideMaxQps(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "maxQps").orElse(DEFAULT_MAX_QPS);
  }

  @Provides
  @Named("standardRateLimiter")
  static RateLimiter provideStandardRateLimiter(@Parameter("maxQps") int maxQps) {
    return RateLimiter.create(maxQps);
  }

  @Provides
  @Parameter("gainingRegistrarId")
  static String provideGainingRegistrarId(HttpServletRequest req) {
    return extractRequiredParameter(req, "gainingRegistrarId");
  }

  @Provides
  @Parameter("losingRegistrarId")
  static String provideLosingRegistrarId(HttpServletRequest req) {
    return extractRequiredParameter(req, "losingRegistrarId");
  }

  @Provides
  @Parameter("bulkTransferDomainNames")
  static ImmutableList<String> provideBulkTransferDomainNames(
      Gson gson, @OptionalJsonPayload Optional<JsonElement> optionalJsonElement) {
    return optionalJsonElement
        .map(je -> ImmutableList.copyOf(gson.fromJson(je, new TypeToken<List<String>>() {})))
        .orElseThrow(
            () -> new BadRequestException("Missing POST body of bulk transfer domain names"));
  }

  @Provides
  @Parameter("requestedByRegistrar")
  static boolean provideRequestedByRegistrar(HttpServletRequest req) {
    return extractBooleanParameter(req, "requestedByRegistrar");
  }

  @Provides
  @Parameter("reason")
  static String provideReason(HttpServletRequest req) {
    return extractRequiredParameter(req, "reason");
  }
}
