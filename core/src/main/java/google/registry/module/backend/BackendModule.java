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

package google.registry.module.backend;

import static google.registry.model.tld.Registries.assertTldExists;
import static google.registry.model.tld.Registries.assertTldsExist;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfParameters;

import com.google.common.collect.ImmutableSet;
import dagger.Module;
import dagger.Provides;
import google.registry.batch.ExpandRecurringBillingEventsAction;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/**
 * Dagger module for injecting common settings for all Backend tasks.
 */
@Module
public class BackendModule {

  @Provides
  @Parameter(RequestParameters.PARAM_TLD)
  static String provideTld(HttpServletRequest req) {
    return assertTldExists(extractRequiredParameter(req, RequestParameters.PARAM_TLD));
  }

  @Provides
  @Parameter(RequestParameters.PARAM_TLDS)
  static ImmutableSet<String> provideTlds(HttpServletRequest req) {
    ImmutableSet<String> tlds = extractSetOfParameters(req, RequestParameters.PARAM_TLDS);
    assertTldsExist(tlds);
    return tlds;
  }

  @Provides
  @Parameter("cursorTime")
  static Optional<DateTime> provideCursorTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(
        req, ExpandRecurringBillingEventsAction.PARAM_CURSOR_TIME);
  }
}
