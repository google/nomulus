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

import static com.google.common.base.Strings.emptyToNull;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfParameters;

import com.google.common.collect.ImmutableSet;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.money.CurrencyUnit;

/**
 * Dagger module for the tools package.
 */
@Module
public class ToolsServerModule {

  @Provides
  @Parameter("clientId")
  static Optional<String> provideClientId(HttpServletRequest req) {
    return Optional.ofNullable(emptyToNull(req.getParameter(CreateGroupsAction.CLIENT_ID_PARAM)));
  }

  @Provides
  @Parameter("fields")
  static Optional<String> provideFields(HttpServletRequest req) {
    return extractOptionalParameter(req, ListObjectsAction.FIELDS_PARAM);
  }

  @Provides
  @Parameter("fullFieldNames")
  static Optional<Boolean> provideFullFieldNames(HttpServletRequest req) {
    String s = emptyToNull(req.getParameter(ListObjectsAction.FULL_FIELD_NAMES_PARAM));
    return (s == null) ? Optional.empty() : Optional.of(Boolean.parseBoolean(s));
  }

  @Provides
  @Parameter("inputData")
  static String provideInput(HttpServletRequest req) {
    return extractRequiredParameter(req, CreatePremiumListAction.INPUT_PARAM);
  }

  @Provides
  @Parameter("premiumListName")
  static String provideName(HttpServletRequest req) {
    return extractRequiredParameter(req, CreatePremiumListAction.NAME_PARAM);
  }

  @Provides
  @Parameter("currency")
  static CurrencyUnit provideCurrency(HttpServletRequest req) {
    return CurrencyUnit.of(extractRequiredParameter(req, CreatePremiumListAction.CURRENCY));
  }

  @Provides
  @Parameter("override")
  static boolean provideOverride(HttpServletRequest req) {
   return extractBooleanParameter(req, CreatePremiumListAction.OVERRIDE_PARAM);
  }

  @Provides
  @Parameter("printHeaderRow")
  static Optional<Boolean> providePrintHeaderRow(HttpServletRequest req) {
    String s = emptyToNull(req.getParameter(ListObjectsAction.PRINT_HEADER_ROW_PARAM));
    return (s == null) ? Optional.empty() : Optional.of(Boolean.parseBoolean(s));
  }

  @Provides
  @Parameter(RequestParameters.PARAM_TLD)
  static String provideTld(HttpServletRequest req) {
    return extractRequiredParameter(req, RequestParameters.PARAM_TLD);
  }

  @Provides
  @Parameter(RequestParameters.PARAM_TLDS)
  static ImmutableSet<String> provideTlds(HttpServletRequest req) {
    return extractSetOfParameters(req, RequestParameters.PARAM_TLDS);
  }

  @Provides
  @Parameter("limit")
  static int provideLimit(HttpServletRequest req) {
    return extractIntParameter(req, "limit");
  }

  @Provides
  @Parameter("rawKeys")
  static String provideRawKeys(HttpServletRequest req) {
    return extractRequiredParameter(req, "rawKeys");
  }

  @Provides
  @Parameter("jobId")
  String provideJobId(HttpServletRequest req) {
    return extractRequiredParameter(req, "jobId");
  }

  @Provides
  @Parameter("smearMinutes")
  static int provideSmearMinutes(HttpServletRequest req) {
    return extractIntParameter(req, "smearMinutes");
  }
}
