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

package google.registry.flows.contact;

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyTargetIdCount;
import static google.registry.model.EppResourceUtils.checkResourcesExist;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.contact.ContactCommand.Check;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CheckData.ContactCheck;
import google.registry.model.eppoutput.CheckData.ContactCheckData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.util.Clock;
import javax.inject.Inject;

/**
 * An EPP flow that checks whether a contact can be provisioned.
 *
 * <p>This flows can check the existence of multiple contacts simultaneously.
 *
 * @error {@link google.registry.flows.exceptions.TooManyResourceChecksException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 */
@ReportingSpec(ActivityReportField.CONTACT_CHECK)
public final class ContactCheckFlow implements Flow {

  @Inject ResourceCommand resourceCommand;
  @Inject @RegistrarId String registrarId;
  @Inject ExtensionManager extensionManager;
  @Inject Clock clock;
  @Inject @Config("maxChecks") int maxChecks;
  @Inject EppResponse.Builder responseBuilder;
  @Inject ContactCheckFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.validate();  // There are no legal extensions for this flow.
    validateRegistrarIsLoggedIn(registrarId);
    ImmutableList<String> targetIds = ((Check) resourceCommand).getTargetIds();
    verifyTargetIdCount(targetIds, maxChecks);
    ImmutableSet<String> existingIds =
        checkResourcesExist(ContactResource.class, targetIds, clock.nowUtc());
    ImmutableList.Builder<ContactCheck> checks = new ImmutableList.Builder<>();
    for (String id : targetIds) {
      boolean unused = !existingIds.contains(id);
      checks.add(ContactCheck.create(unused, id, unused ? null : "In use"));
    }
    return responseBuilder.setResData(ContactCheckData.create(checks.build())).build();
  }
}
