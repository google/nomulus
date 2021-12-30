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

package google.registry.flows.domain;

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.domain.DomainTransferUtils.createTransferResponse;

import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.ResourceFlowUtils;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.NoTransferHistoryToQueryException;
import google.registry.flows.exceptions.NotAuthorizedToViewTransferException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that queries a pending transfer on a domain.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. This flow can be used by the gaining or losing registrars (or
 * anyone with the correct authId) to see the status of a transfer, which may still be pending or
 * may have been approved, rejected, cancelled or implicitly approved by virtue of the transfer
 * period expiring.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.NoTransferHistoryToQueryException}
 * @error {@link google.registry.flows.exceptions.NotAuthorizedToViewTransferException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_TRANSFER_QUERY)
public final class DomainTransferQueryFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainTransferQueryFlow() {}

  @Override
  public EppResponse run() throws EppException {
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate(); // There are no legal extensions for this flow.
    DateTime now = clock.nowUtc();
    DomainBase domain = loadAndVerifyExistence(DomainBase.class, targetId, now);
    verifyOptionalAuthInfo(authInfo, domain);
    // Most of the fields on the transfer response are required, so there's no way to return valid
    // XML if the object has never been transferred (and hence the fields aren't populated).
    DomainTransferData transferData = domain.getTransferData();
    if (transferData.getTransferStatus() == null) {
      throw new NoTransferHistoryToQueryException();
    }
    // Note that the authorization info on the command (if present) has already been verified. If
    // it's present, then the other checks are unnecessary.
    if (!authInfo.isPresent()
        && !registrarId.equals(transferData.getGainingRegistrarId())
        && !registrarId.equals(transferData.getLosingRegistrarId())) {
      throw new NotAuthorizedToViewTransferException();
    }
    DateTime newExpirationTime = null;
    if (transferData.getTransferStatus().isApproved()) {
      newExpirationTime = transferData.getTransferredRegistrationExpirationTime();
    } else if (transferData.getTransferStatus().equals(TransferStatus.PENDING)) {
      newExpirationTime =
          ResourceFlowUtils.computeExDateForApprovalTime(
              domain, now, domain.getTransferData().getTransferPeriod());
    }
    return responseBuilder
        .setResData(createTransferResponse(targetId, transferData, newExpirationTime))
        .build();
  }
}
