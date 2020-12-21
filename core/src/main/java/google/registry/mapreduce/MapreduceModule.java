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

package google.registry.mapreduce;

import static google.registry.mapreduce.MapreduceRunner.PARAM_DRY_RUN;
import static google.registry.mapreduce.MapreduceRunner.PARAM_FAST;
import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.mapreduce.MapreduceRunner.PARAM_REDUCE_SHARDS;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for the mapreduce package. */
@Module
public final class MapreduceModule {

  @Provides
  @Parameter(PARAM_DRY_RUN)
  static boolean provideIsDryRun(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_DRY_RUN);
  }

  @Provides
  @Parameter(PARAM_FAST)
  static boolean provideIsFast(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_FAST);
  }

  @Provides
  @Parameter(PARAM_MAP_SHARDS)
  static Optional<Integer> provideMapShards(HttpServletRequest req) {
    return extractOptionalIntParameter(req, PARAM_MAP_SHARDS);
  }

  @Provides
  @Parameter(PARAM_REDUCE_SHARDS)
  static Optional<Integer> provideReduceShards(HttpServletRequest req) {
    return extractOptionalIntParameter(req, PARAM_REDUCE_SHARDS);
  }
}
