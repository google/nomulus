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

package google.registry.flows;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.tld.label.ReservationType.getTypeOfHighestSeverity;
import static google.registry.model.tld.label.ReservedList.getReservationTypes;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.AVAILABLE;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.REGISTERED;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.RESERVED;
import static google.registry.monitoring.whitebox.CheckApiMetric.Status.INVALID_NAME;
import static google.registry.monitoring.whitebox.CheckApiMetric.Status.INVALID_REGISTRY_PHASE;
import static google.registry.monitoring.whitebox.CheckApiMetric.Status.SUCCESS;
import static google.registry.monitoring.whitebox.CheckApiMetric.Status.UNKNOWN_ERROR;
import static google.registry.monitoring.whitebox.CheckApiMetric.Tier.PREMIUM;
import static google.registry.monitoring.whitebox.CheckApiMetric.Tier.STANDARD;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static org.json.simple.JSONValue.toJSONString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import com.google.common.net.MediaType;
import dagger.Module;
import dagger.Provides;
import google.registry.flows.domain.DomainFlowUtils.BadCommandForRegistryPhaseException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.model.domain.DomainBase;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.tld.Registry;
import google.registry.model.tld.label.ReservationType;
import google.registry.monitoring.whitebox.CheckApiMetric;
import google.registry.monitoring.whitebox.CheckApiMetric.Availability;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/**
 * An action that returns availability and premium checks as JSON.
 *
 * <p>This action returns plain JSON without a safety prefix, so it's vital that the output not be
 * user controlled, lest it open an XSS vector. Do not modify this to return the domain name in the
 * response.
 */
@Action(service = Action.Service.PUBAPI, path = "/check", auth = Auth.AUTH_PUBLIC_ANONYMOUS)
public class CheckApiAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  @Parameter("domain")
  String domain;

  @Inject Response response;
  @Inject Clock clock;
  @Inject CheckApiMetric.Builder metricBuilder;
  @Inject CheckApiMetrics checkApiMetrics;

  @Inject
  CheckApiAction() {}

  @Override
  public void run() {
    try {
      response.setHeader("Content-Disposition", "attachment");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
      response.setContentType(MediaType.JSON_UTF_8);
      response.setPayload(toJSONString(doCheck()));
    } finally {
      CheckApiMetric metric = metricBuilder.build();
      checkApiMetrics.incrementCheckApiRequest(metric);
      checkApiMetrics.recordProcessingTime(metric);
    }
  }

  private Map<String, Object> doCheck() {
    String domainString;
    InternetDomainName domainName;
    try {
      domainString = canonicalizeDomainName(nullToEmpty(domain));
      domainName = validateDomainName(domainString);
    } catch (IllegalArgumentException | EppException e) {
      metricBuilder.status(INVALID_NAME);
      return fail("Must supply a valid domain name on an authoritative TLD");
    }
    try {
      // Throws an EppException with a reasonable error message which will be sent back to caller.
      validateDomainNameWithIdnTables(domainName);

      DateTime now = clock.nowUtc();
      Registry registry = Registry.get(domainName.parent().toString());
      try {
        verifyNotInPredelegation(registry, now);
      } catch (BadCommandForRegistryPhaseException e) {
        metricBuilder.status(INVALID_REGISTRY_PHASE);
        return fail("Check in this TLD is not allowed in the current registry phase");
      }

      boolean isRegistered = checkExists(domainString, now);
      Optional<String> reservedError = Optional.empty();
      boolean isReserved = false;
      if (!isRegistered) {
        reservedError = checkReserved(domainName);
        isReserved = reservedError.isPresent();
      }
      Availability availability = isRegistered ? REGISTERED : (isReserved ? RESERVED : AVAILABLE);
      String errorMsg = isRegistered ? "In use" : (isReserved ? reservedError.get() : null);

      ImmutableMap.Builder<String, Object> responseBuilder = new ImmutableMap.Builder<>();
      metricBuilder.status(SUCCESS).availability(availability);
      responseBuilder.put("status", "success").put("available", availability.equals(AVAILABLE));

      boolean isPremium = isDomainPremium(domainString, now);
      metricBuilder.tier(isPremium ? PREMIUM : STANDARD);
      responseBuilder.put("tier", isPremium ? "premium" : "standard");
      if (!AVAILABLE.equals(availability)) {
        responseBuilder.put("reason", errorMsg);
      }
      return responseBuilder.build();
    } catch (InvalidIdnDomainLabelException e) {
      metricBuilder.status(INVALID_NAME);
      return fail(e.getResult().getMsg());
    } catch (Exception e) {
      metricBuilder.status(UNKNOWN_ERROR);
      logger.atWarning().withCause(e).log("Unknown error.");
      return fail("Invalid request");
    }
  }

  private boolean checkExists(String domainString, DateTime now) {
    return !ForeignKeyIndex.loadCached(DomainBase.class, ImmutableList.of(domainString), now)
        .isEmpty();
  }

  private Optional<String> checkReserved(InternetDomainName domainName) {
    ImmutableSet<ReservationType> reservationTypes =
        getReservationTypes(domainName.parts().get(0), domainName.parent().toString());
    if (!reservationTypes.isEmpty()) {
      return Optional.of(getTypeOfHighestSeverity(reservationTypes).getMessageForCheck());
    }
    return Optional.empty();
  }

  private Map<String, Object> fail(String reason) {
    return ImmutableMap.of("status", "error", "reason", reason);
  }

  /** Dagger module for the check api endpoint. */
  @Module
  public static final class CheckApiModule {

    @Provides
    @Parameter("domain")
    static String provideDomain(HttpServletRequest req) {
      return RequestParameters.extractRequiredParameter(req, "domain");
    }
  }
}
