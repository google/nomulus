// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.quota;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException;
import google.registry.flows.Flow;
import google.registry.model.eppinput.EppInput;
import google.registry.quota.QuotaManager;
import java.time.Duration;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Quota management for EPP flows using Redis/Valkey.
 *
 * <p>This is provided the base {@link QuotaManager} as well as a list of {@link
 * FlowQuotaParameters}. The latter is used to define per-flow throttling characteristics so that we
 * can check quota before the flow class itself is instantiated.
 */
@ThreadSafe
public class FlowQuotaManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final QuotaManager quotaManager;
  private final ImmutableMap<Class<? extends Flow>, FlowQuotaParameters> parametersMap;

  public static FlowQuotaManager create(
      QuotaManager quotaManager, ImmutableList<FlowQuotaParameters> allParameters) {
    ImmutableMap<Class<? extends Flow>, FlowQuotaParameters> parametersMap =
        allParameters.stream()
            .collect(ImmutableMap.toImmutableMap(FlowQuotaParameters::getFlowClass, p -> p));
    return new FlowQuotaManager(quotaManager, parametersMap);
  }

  private FlowQuotaManager(
      QuotaManager quotaManager,
      ImmutableMap<Class<? extends Flow>, FlowQuotaParameters> parametersMap) {
    this.quotaManager = quotaManager;
    this.parametersMap = parametersMap;
  }

  /** Acquires one unit of quota from the quota manager. Throws an exception on failure. */
  public void acquireQuota(Class<? extends Flow> flowClass, EppInput eppInput, String registrarId)
      throws TooManyRequestsException {
    FlowQuotaParameters parameters = parametersMap.get(flowClass);
    if (parameters == null) {
      return;
    }
    String quotaId = parameters.getQuotaId(eppInput, registrarId);
    int maxQuotaAllowed = parameters.getMaxQuotaAllowed();
    Duration windowDuration = parameters.getWindowDuration();
    if (!quotaManager.acquireQuota(quotaId, maxQuotaAllowed, windowDuration)) {
      logger.atWarning().log(
          "Failed to acquire quota for flow %s, registrar %s",
          flowClass.getSimpleName(), registrarId);
      throw new TooManyRequestsException();
    }
  }

  /** Too many requests too quickly. */
  public static class TooManyRequestsException extends EppException.CommandUseErrorException {
    public TooManyRequestsException() {
      super("Too many requests");
    }
  }
}
