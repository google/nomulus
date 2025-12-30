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

package google.registry.tmch;

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.io.Resources.getResource;
import static com.google.common.io.Resources.readLines;
import static google.registry.tmch.RstTmchUtils.RstEnvironment.OTE;
import static google.registry.tmch.RstTmchUtils.RstEnvironment.PROD;
import static google.registry.util.RegistryEnvironment.SANDBOX;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.tmch.ClaimsList;
import google.registry.util.RegistryEnvironment;
import java.io.IOException;
import java.net.URL;
import java.util.Locale;
import java.util.Optional;

/**
 * Utilities supporting TMCH-related RST testing in the Sandbox environment.
 *
 * <p>For logistic reasons we must conduct RST testing in the Sandbox environments. RST tests
 * require the use of special labels hosted on their website. To isolate these labels from regular
 * customers conducting onboarding tests, we manually download the test files as resources, and
 * serve them up only to RST TLDs.
 */
public class RstTmchUtils {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * The RST environments.
   *
   * <p>We conduct both OTE and PROD RST tests in Sandbox.
   */
  enum RstEnvironment {
    OTE,
    PROD
  }

  private static final ImmutableMap<RstEnvironment, Supplier<Optional<ClaimsList>>> CLAIMS_CACHE =
      ImmutableMap.of(
          OTE, memoize(() -> getClaimsList(OTE)), PROD, memoize(() -> getClaimsList(PROD)));

  private static final ImmutableMap<RstEnvironment, Supplier<Optional<SignedMarkRevocationList>>>
      SMDRL_CACHE =
          ImmutableMap.of(
              OTE, memoize(() -> getSmdrList(OTE)), PROD, memoize(() -> getSmdrList(PROD)));

  /** Returns appropriate test labels if {@code tld} is for RST testing; otherwise returns empty. */
  public static Optional<ClaimsList> getClaimsList(String tld) {
    return getRstEnvironment(tld).map(CLAIMS_CACHE::get).flatMap(Supplier::get);
  }

  /** Returns appropriate test labels if {@code tld} is for RST testing; otherwise returns empty. */
  public static Optional<SignedMarkRevocationList> getSmdrList(String tld) {
    return getRstEnvironment(tld).map(SMDRL_CACHE::get).flatMap(Supplier::get);
  }

  static Optional<RstEnvironment> getRstEnvironment(String tld) {
    if (!RegistryEnvironment.get().equals(SANDBOX)) {
      return Optional.empty();
    }
    if (tld.startsWith("cc-rst-test-")) {
      return Optional.of(OTE);
    }
    if (tld.startsWith("zz--")) {
      return Optional.of(PROD);
    }
    return Optional.empty();
  }

  private static Optional<ClaimsList> getClaimsList(RstEnvironment rstEnvironment) {
    if (!RegistryEnvironment.get().equals(SANDBOX)) {
      return Optional.empty();
    }
    String resourceName = rstEnvironment.name().toLowerCase(Locale.ROOT) + ".rst.dnl.csv";
    URL resource = getResource(RstTmchUtils.class, resourceName);
    try {
      return Optional.of(ClaimsListParser.parse(readLines(resource, UTF_8)));
    } catch (IOException e) {
      // Do not throw.
      logger.atSevere().withCause(e).log(
          "Could not load Claims list for %s in Sandbox.", rstEnvironment);
      return Optional.empty();
    }
  }

  private static Optional<SignedMarkRevocationList> getSmdrList(RstEnvironment rstEnvironment) {
    if (!RegistryEnvironment.get().equals(SANDBOX)) {
      return Optional.empty();
    }
    String resourceName = rstEnvironment.name().toLowerCase(Locale.ROOT) + ".rst.smdrl.csv";
    URL resource = getResource(RstTmchUtils.class, resourceName);
    try {
      return Optional.of(SmdrlCsvParser.parse(readLines(resource, UTF_8)));
    } catch (IOException e) {
      // Do not throw.
      logger.atSevere().withCause(e).log(
          "Could not load Claims list for %s in Sandbox.", rstEnvironment);
      return Optional.empty();
    }
  }
}
