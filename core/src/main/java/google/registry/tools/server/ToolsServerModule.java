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

package google.registry.tools.server;

import static com.google.common.base.Strings.emptyToNull;
import static google.registry.request.RequestParameters.extractIntParameter;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import google.registry.tools.server.UpdateUserGroupAction.Mode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.joda.time.DateTime;

/** Dagger module for the tools package. */
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
  @Parameter("printHeaderRow")
  static Optional<Boolean> providePrintHeaderRow(HttpServletRequest req) {
    String s = emptyToNull(req.getParameter(ListObjectsAction.PRINT_HEADER_ROW_PARAM));
    return (s == null) ? Optional.empty() : Optional.of(Boolean.parseBoolean(s));
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
  @Parameter("refreshQps")
  static Optional<Integer> provideRefreshQps(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "refreshQps");
  }

  @Provides
  @Parameter("activeOrDeletedSince")
  static Optional<DateTime> provideDeletionTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(req, "activeOrDeletedSince");
  }

  @Provides
  static Mode provideGroupUpdateMode(HttpServletRequest req) {
    return Mode.valueOf(extractRequiredParameter(req, "groupUpdateMode"));
  }

  @Provides
  @Parameter("userEmailAddress")
  static String provideUserEmailAddress(HttpServletRequest req) {
    return extractRequiredParameter(req, "userEmailAddress");
  }

  @Provides
  @Parameter("groupEmailAddress")
  static String provideGroupEmailAddress(HttpServletRequest req) {
    return extractRequiredParameter(req, "groupEmailAddress");
  }
}
