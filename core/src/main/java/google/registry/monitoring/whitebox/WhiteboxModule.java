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

package google.registry.monitoring.whitebox;

import dagger.Module;
import dagger.Provides;
import google.registry.util.Clock;

/**
 * Dagger module for injecting common settings for Whitebox tasks.
 */
@Module
public class WhiteboxModule {

  /** Provides an EppMetric builder with the request ID and startTimestamp already initialized. */
  @Provides
  static EppMetric.Builder provideEppMetricBuilder(Clock clock) {
    return EppMetric.builderForRequest(clock);
  }

  @Provides
  static CheckApiMetric.Builder provideCheckApiMetricBuilder(Clock clock) {
    return CheckApiMetric.builder(clock);
  }
}
