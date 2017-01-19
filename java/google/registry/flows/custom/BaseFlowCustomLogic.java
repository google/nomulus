// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;

/**
 * An abstract base class for all flow custom logic that stores the flow's {@link EppInput} and
 * {@link SessionMetadata} for convenience. Both of these are immutable.
 */
public abstract class BaseFlowCustomLogic {

  private final EppInput eppInput;
  private final SessionMetadata sessionMetadata;
  private final boolean isSuperuser;

  protected BaseFlowCustomLogic(EppInput eppInput, SessionMetadata sessionMetadata, boolean isSuperuser) {
    this.eppInput = eppInput;
    this.sessionMetadata = sessionMetadata;
    this.isSuperuser = isSuperuser;
  }

  protected EppInput getEppInput() {
    return eppInput;
  }

  protected SessionMetadata getSessionMetadata() {
    return sessionMetadata;
  }

  protected boolean isSuperuser() {
    return isSuperuser;
  }
}
