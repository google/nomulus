// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;


import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for the Registrar Console parameters. */
@Module
public final class RegistrarConsoleModule {

  static final String PARAM_CLIENT_ID = "clientId";

  @Provides
  @Parameter(PARAM_CLIENT_ID)
  static Optional<String> provideOptionalClientId(HttpServletRequest req) {
    return extractOptionalParameter(req, PARAM_CLIENT_ID);
  }

  @Provides
  @Parameter(PARAM_CLIENT_ID)
  static String provideClientId(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_CLIENT_ID);
  }

  @Provides
  @Parameter("ianaId")
  static Optional<Integer> provideOptionalIanaId(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "ianaId");
  }

  @Provides
  @Parameter("billingAccount")
  static Optional<String> provideOptionalBillingAccount(HttpServletRequest req) {
    return extractOptionalParameter(req, "billingAccount");
  }

  @Provides
  @Parameter("name")
  static Optional<String> provideOptionalName(HttpServletRequest req) {
    return extractOptionalParameter(req, "name");
  }

  @Provides
  @Parameter("driveId")
  static Optional<String> provideOptionalDriveId(HttpServletRequest req) {
    return extractOptionalParameter(req, "driveId");
  }

  @Provides
  @Parameter("referralEmail")
  static Optional<String> provideOptionalReferralEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "referralEmail");
  }

  @Provides
  @Parameter("consoleUserEmail")
  static Optional<String> provideOptionalConsoleUserEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "consoleUserEmail");
  }

  @Provides
  @Parameter("email")
  static Optional<String> provideOptionalEmail(HttpServletRequest req) {
    return extractOptionalParameter(req, "email");
  }

  @Provides
  @Parameter("email")
  static String provideEmail(HttpServletRequest req) {
    return extractRequiredParameter(req, "email");
  }

  @Provides
  @Parameter("street1")
  static Optional<String> provideOptionalStreet1(HttpServletRequest req) {
    return extractOptionalParameter(req, "street1");
  }

  @Provides
  @Parameter("street2")
  static Optional<String> provideOptionalStreet2(HttpServletRequest req) {
    return extractOptionalParameter(req, "street2");
  }

  @Provides
  @Parameter("street3")
  static Optional<String> provideOptionalStreet3(HttpServletRequest req) {
    return extractOptionalParameter(req, "street3");
  }

  @Provides
  @Parameter("city")
  static Optional<String> provideOptionalCity(HttpServletRequest req) {
    return extractOptionalParameter(req, "city");
  }

  @Provides
  @Parameter("state")
  static Optional<String> provideOptionalState(HttpServletRequest req) {
    return extractOptionalParameter(req, "state");
  }

  @Provides
  @Parameter("zip")
  static Optional<String> provideOptionalZip(HttpServletRequest req) {
    return extractOptionalParameter(req, "zip");
  }

  @Provides
  @Parameter("countryCode")
  static Optional<String> provideOptionalCountryCode(HttpServletRequest req) {
    return extractOptionalParameter(req, "countryCode");
  }

  @Provides
  @Parameter("password")
  static Optional<String> provideOptionalPassword(HttpServletRequest req) {
    return extractOptionalParameter(req, "password");
  }

  @Provides
  @Parameter("passcode")
  static Optional<String> provideOptionalPasscode(HttpServletRequest req) {
    return extractOptionalParameter(req, "passcode");
  }

  @Provides
  @Parameter("lockVerificationCode")
  static String provideLockVerificationCode(HttpServletRequest req) {
    return extractRequiredParameter(req, "lockVerificationCode");
  }

  @Provides
  @Parameter("isLock")
  static Boolean provideIsLock(HttpServletRequest req) {
    return Boolean.parseBoolean(extractRequiredParameter(req, "isLock"));
  }
}
