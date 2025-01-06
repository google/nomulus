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

package google.registry.flows.custom;

import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An abstract base class for all flow custom logic that stores the flow's {@link EppInput} and
 * {@link SessionMetadata} for convenience. Both of these are immutable.
 */
public abstract class BaseFlowCustomLogic {

  @Nullable private final EppInput eppInput;
  @Nullable private final SessionMetadata sessionMetadata;
  @Nullable private final FlowMetadata flowMetadata;

  /**
   * Constructs a BaseFlowCustomLogic for the specified EPP flow state.
   *
   * <p>Note that it is possible for the EPP flow state to be absent, which happens when the custom
   * logic is running outside the context of an EPP flow (e.g. {@link DomainPricingCustomLogic} in
   * backend actions).
   */
  protected BaseFlowCustomLogic(
      @Nullable EppInput eppInput,
      @Nullable SessionMetadata sessionMetadata,
      @Nullable FlowMetadata flowMetadata) {
    this.eppInput = eppInput;
    this.sessionMetadata = sessionMetadata;
    this.flowMetadata = flowMetadata;
  }

  /** Returns the {@link EppInput}, which may be empty outside a flow context. */
  protected Optional<EppInput> getEppInput() {
    return Optional.ofNullable(eppInput);
  }

  /** Returns the {@link SessionMetadata}, which may be empty outside a flow context. */
  protected Optional<SessionMetadata> getSessionMetadata() {
    return Optional.ofNullable(sessionMetadata);
  }

  /** Returns the {@link FlowMetadata}, which may be empty outside a flow context. */
  protected Optional<FlowMetadata> getFlowMetadata() {
    return Optional.ofNullable(flowMetadata);
  }
}
