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

package google.registry.cron;

import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfParameters;

import com.google.common.collect.ImmutableSet;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Dagger module for the cron package. */
@Module
public final class CronModule {

  public static final String ENDPOINT_PARAM = "endpoint";
  public static final String QUEUE_PARAM = "queue";
  public static final String FOR_EACH_REAL_TLD_PARAM = "forEachRealTld";
  public static final String FOR_EACH_TEST_TLD_PARAM = "forEachTestTld";
  public static final String RUN_IN_EMPTY_PARAM = "runInEmpty";
  public static final String EXCLUDE_PARAM = "exclude";
  public static final String JITTER_SECONDS_PARAM = "jitterSeconds";

  @Provides
  @Parameter(ENDPOINT_PARAM)
  static String provideEndpoint(HttpServletRequest req) {
    return extractRequiredParameter(req, ENDPOINT_PARAM);
  }

  @Provides
  @Parameter(EXCLUDE_PARAM)
  static ImmutableSet<String> provideExcludes(HttpServletRequest req) {
    return extractSetOfParameters(req, EXCLUDE_PARAM);
  }

  @Provides
  @Parameter(QUEUE_PARAM)
  static String provideQueue(HttpServletRequest req) {
    return extractRequiredParameter(req, QUEUE_PARAM);
  }

  @Provides
  @Parameter(RUN_IN_EMPTY_PARAM)
  static boolean provideRunInEmpty(HttpServletRequest req) {
    return extractBooleanParameter(req, RUN_IN_EMPTY_PARAM);
  }

  @Provides
  @Parameter(FOR_EACH_REAL_TLD_PARAM)
  static boolean provideForEachRealTld(HttpServletRequest req) {
    return extractBooleanParameter(req, FOR_EACH_REAL_TLD_PARAM);
  }

  @Provides
  @Parameter(FOR_EACH_TEST_TLD_PARAM)
  static boolean provideForEachTestTld(HttpServletRequest req) {
    return extractBooleanParameter(req, FOR_EACH_TEST_TLD_PARAM);
  }

  @Provides
  @Parameter(JITTER_SECONDS_PARAM)
  static Optional<Integer> provideJitterSeconds(HttpServletRequest req) {
    return extractOptionalIntParameter(req, JITTER_SECONDS_PARAM);
  }
}
