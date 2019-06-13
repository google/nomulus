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

import static google.registry.util.TypeUtils.getClassFromString;
import static google.registry.util.TypeUtils.instantiate;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import java.io.IOException;
import java.io.Reader;
import javax.servlet.http.HttpServletRequest;

/**
 * Dagger module for the whois package.
 *
 * <h3>Dependencies</h3>
 *
 * <ul>
 *   <li>{@link google.registry.request.RequestModule RequestModule}
 * </ul>
 *
 * @see "google.registry.module.frontend.FrontendComponent"
 */
@Module
public final class WhoisModule {

  @Provides
  @SuppressWarnings("CloseableProvides")
  static Reader provideHttpInputReader(HttpServletRequest req) {
    try {
      return req.getReader();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Config("whoisCommandFactory")
  static WhoisCommandFactory provideWhoisCommandFactory(
      @Config("whoisCommandFactoryClass") String factoryClass) {
    return instantiate(getClassFromString(factoryClass, WhoisCommandFactory.class));
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
