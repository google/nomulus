// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import com.google.common.collect.ImmutableSortedMap;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.CertificateChecker;
import google.registry.util.Clock;
import javax.inject.Singleton;
import org.joda.time.DateTime;

/** Dagger module that provides the {@link CertificateChecker} used in the application. */
// TODO(sarahbot@): Move this module to a better location. Possibly flows/. If we decide to move
// CertificateChecker.java to core/ delete this file and inject the CertificateChecker constructor
// instead.
@Module
public abstract class CertificateCheckerModule {

  @Provides
  @Singleton
  static CertificateChecker provideCertificateChecker(
      @Config("maxValidityDaysSchedule") ImmutableSortedMap<DateTime, Integer> validityDaysMap,
      @Config("expirationWarningDays") int daysToExpiration,
      @Config("minimumRsaKeyLength") int minimumRsaKeyLength,
      Clock clock) {
    return new CertificateChecker(validityDaysMap, daysToExpiration, minimumRsaKeyLength, clock);
  }

  private CertificateCheckerModule() {}
}
