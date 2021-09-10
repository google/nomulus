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

package google.registry.flows.custom;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;
import google.registry.model.poll.PollMessage;

/** A class to customize {@link DomainCreateFlowCustomLogic} for testing. */
public class TestDomainCreateFlowCustomLogic extends DomainCreateFlowCustomLogic {

  protected TestDomainCreateFlowCustomLogic(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    super(eppInput, sessionMetadata, flowMetadata);
  }

  @Override
  public EntityChanges beforeSave(BeforeSaveParameters parameters) {
    if (parameters.newDomain().getDomainName().startsWith("custom-logic-test")) {
      PollMessage extraPollMessage =
          new PollMessage.OneTime.Builder()
              .setParent(parameters.historyEntry())
              .setEventTime(tm().getTransactionTime())
              .setRegistrarId(getSessionMetadata().getRegistrarId())
              .setMsg("Custom logic was triggered")
              .build();
      return EntityChanges.newBuilder()
          .setSaves(parameters.entityChanges().getSaves())
          .addSave(extraPollMessage)
          .setDeletes(parameters.entityChanges().getDeletes())
          .build();
    }
    return parameters.entityChanges();
  }
}
