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

import google.registry.flows.Flow;
import google.registry.model.eppinput.EppInput;
import java.time.Duration;

/**
 * Provider of information about how we should throttle / have quota for a given flow type.
 *
 * <p>Normally, we'd wish to define these in the flow class itself by using some interface that
 * might be named something like "ThrottlingFlow". However, the flow class itself is not
 * instantiated until a transaction has been opened (if the flow is a transactional flow). This is
 * suboptimal, given that one of the primary purposes of throttling is to reduce database load. As a
 * result, we define the throttling parameters outside the flow class itself so the throttling /
 * quota can be checked before the class is actually instantiated.
 */
public interface FlowQuotaParameters {

  /** The flow that is being throttled. */
  Class<? extends Flow> getFlowClass();

  /**
   * The ID of the quota, e.g. a domain-name-registrar-ID combo.
   *
   * <p>Note: the registrar ID may be empty if we're not authenticated yet, like a LoginFlow.
   */
  String getQuotaId(EppInput eppInput, String registrarId);

  /** The maximum number of tokens / quota for each ID for this flow. */
  int getMaxQuotaAllowed();

  /** The fixed window over which to throttle requests with the same ID. */
  Duration getWindowDuration();
}
