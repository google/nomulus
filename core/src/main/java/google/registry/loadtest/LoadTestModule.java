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

package google.registry.loadtest;

import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.Minutes;

/**
 * Dagger module for loadtest package.
 *
 * @see "google.registry.module.backend.ToolsComponent"
 */
@Module
public final class LoadTestModule {

  // There's already an @Parameter("clientId") for CreateGroupsAction that's only optional, and we
  // want this one to be required, so give it a different name.
  @Provides
  @Parameter("loadtestClientId")
  static String provideClientId(HttpServletRequest req) {
    return extractRequiredParameter(req, "clientId");
  }

  @Provides
  @Parameter("delaySeconds")
  static int provideDelaySeconds(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "delaySeconds")
        .orElse(120);
  }

  @Provides
  @Parameter("runSeconds")
  static int provideRunSeconds(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "runSeconds")
        .orElse(Minutes.ONE.toStandardSeconds().getSeconds());
  }

  @Provides
  @Parameter("successfulDomainCreates")
  static int provideSuccessfulDomainCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "successfulDomainCreates").orElse(0);
  }

  @Provides
  @Parameter("failedDomainCreates")
  static int provideFailedDomainCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "failedDomainCreates").orElse(0);
  }

  @Provides
  @Parameter("domainInfos")
  static int provideDomainInfos(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "domainInfos").orElse(0);
  }

  @Provides
  @Parameter("domainChecks")
  static int provideDomainChecks(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "domainChecks").orElse(0);
  }

  @Provides
  @Parameter("successfulContactCreates")
  static int provideSuccessfulContactCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "successfulContactCreates").orElse(0);
  }

  @Provides
  @Parameter("failedContactCreates")
  static int provideFailedContactCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "failedContactCreates").orElse(0);
  }

  @Provides
  @Parameter("contactInfos")
  static int provideContactInfos(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "contactInfos").orElse(0);
  }

  @Provides
  @Parameter("successfulHostCreates")
  static int provideSuccessfulHostCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "successfulHostCreates").orElse(0);
  }

  @Provides
  @Parameter("failedHostCreates")
  static int provideFailedHostCreates(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "failedHostCreates").orElse(0);
  }

  @Provides
  @Parameter("hostInfos")
  static int provideHostInfos(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "hostInfos").orElse(0);
  }
}
