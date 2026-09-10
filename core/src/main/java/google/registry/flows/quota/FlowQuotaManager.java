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

import com.google.common.base.Ascii;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException;
import google.registry.flows.Flow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.model.eppinput.EppInput;
import google.registry.quota.QuotaManager;
import java.time.Duration;
import javax.annotation.concurrent.ThreadSafe;

/** Quota management for EPP flows using Redis/Valkey. */
@ThreadSafe
public class FlowQuotaManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final QuotaManager quotaManager;
  private final int domainCreateThrottleWindowTokens;
  private final Duration domainCreateThrottleWindowDuration;

  public FlowQuotaManager(
      QuotaManager quotaManager,
      int domainCreateThrottleWindowTokens,
      Duration domainCreateThrottleWindowDuration) {
    this.quotaManager = quotaManager;
    this.domainCreateThrottleWindowTokens = domainCreateThrottleWindowTokens;
    this.domainCreateThrottleWindowDuration = domainCreateThrottleWindowDuration;
  }

  /** Acquires one unit of quota from the quota manager. Throws an exception on failure. */
  public void acquireQuota(Class<? extends Flow> flowClass, EppInput eppInput, String registrarId)
      throws EppException {
    // For now at least, we only throttle domain:create requests
    if (!flowClass.equals(DomainCreateFlow.class)) {
      return;
    }
    String quotaId = getDomainCreateQuotaId(eppInput, registrarId);
    if (!quotaManager.acquireQuota(
        quotaId, domainCreateThrottleWindowTokens, domainCreateThrottleWindowDuration)) {
      logger.atWarning().log("Failed to acquire domain-create quota for %s", quotaId);
      throw new TooManyRequestsException();
    }
  }

  private String getDomainCreateQuotaId(EppInput eppInput, String registrarId)
      throws MissingTargetIdException {
    // Normalize the domain names to lowercase. We're not concerned about total canonicalization or
    // verification, as that's caught in the flow itself.
    String domainName =
        Ascii.toLowerCase(
            eppInput.getSingleTargetId().orElseThrow(MissingTargetIdException::new).trim());
    return String.format("%s:%s", registrarId, domainName);
  }

  /** Too many requests too quickly. */
  public static class TooManyRequestsException extends EppException.CommandUseErrorException {
    public TooManyRequestsException() {
      super("Too many requests for this domain");
    }
  }

  /** Thrown when a command target identifier (domain name) is missing. */
  public static class MissingTargetIdException
      extends EppException.RequiredParameterMissingException {
    public MissingTargetIdException() {
      super("Required target identifier is missing");
    }
  }
}
