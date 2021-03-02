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

package google.registry.whois;

import dagger.Module;
import dagger.Provides;
import google.registry.util.Clock;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import java.io.IOException;
import java.io.Reader;
import javax.servlet.http.HttpServletRequest;

/**
 * Dagger base module for the whois package.
 *
 * <p>Provides whois objects common to both the normal ({@link WhoisModule}) and non-caching ({@link
 * NonCachingWhoisModule}) implementations.
 */
@Module
class BaseWhoisModule {

  @Provides
  @SuppressWarnings("CloseableProvides")
  static Reader provideHttpInputReader(HttpServletRequest req) {
    try {
      return req.getReader();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Provides a {@link WhoisMetrics.WhoisMetric.Builder} with the startTimestamp already
   * initialized.
   */
  @Provides
  static WhoisMetric.Builder provideEppMetricBuilder(Clock clock) {
    return WhoisMetric.builderForRequest(clock);
  }
}
