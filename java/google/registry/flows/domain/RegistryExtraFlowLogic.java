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

package google.registry.flows.domain;

import google.registry.flows.EppException;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.eppinput.EppInput;
import google.registry.model.reporting.HistoryEntry;
import java.util.Set;
import org.joda.time.DateTime;

/**
 * Interface for classes which provide extra registry logic for things like TLD-specific rules and
 * discounts.
 */
@Deprecated
public interface RegistryExtraFlowLogic {

  /** Gets the flags to be returned for application info commands. */
  public Set<String> getApplicationExtensionFlags(
      DomainApplication application, String clientId, DateTime asOfDate);

  /** Gets the flags to be returned for domain info commands. */
  public Set<String> getExtensionFlags(
      DomainResource domainResource, String clientId, DateTime asOfDate);

  /** Performs additional tasks required for an application create command. */
  public void performAdditionalApplicationCreateLogic(
      DomainApplication application,
      String clientId,
      int years,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for an application delete command. */
  public void performAdditionalApplicationDeleteLogic(
      DomainApplication application,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Computes the expected application update fee. */
  public BaseFee getApplicationUpdateFeeOrCredit(
      DomainApplication application,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput) throws EppException;

  /** Performs additional tasks required for an application update command. */
  public void performAdditionalApplicationUpdateLogic(
      DomainApplication application,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for an allocate command. */
  public void performAdditionalDomainAllocateLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      int years,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Computes the expected creation fee. */
  public BaseFee getCreateFeeOrCredit(
      String domainName,
      String clientId,
      DateTime asOfDate,
      int years,
      EppInput eppInput) throws EppException;

  /** Performs additional tasks required for a create command. */
  public void performAdditionalDomainCreateLogic(
      DomainResource domain,
      String clientId,
      int years,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a delete command. */
  public void performAdditionalDomainDeleteLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Computes the expected renewal fee. */
  public BaseFee getRenewFeeOrCredit(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      int years,
      EppInput eppInput) throws EppException;

  /** Performs additional tasks required for a renew command. */
  public void performAdditionalDomainRenewLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      int years,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a restore command. */
  public void performAdditionalDomainRestoreLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a domain transfer approve command. */
  public void performAdditionalDomainTransferApproveLogic(
      DomainResource domain,
      String clientId,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a domain transfer cancel command. */
  public void performAdditionalDomainTransferCancelLogic(
      DomainResource domain,
      String clientId,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a domain transfer reject command. */
  public void performAdditionalDomainTransferRejectLogic(
      DomainResource domain,
      String clientId,
      HistoryEntry historyEntry) throws EppException;

  /** Performs additional tasks required for a transfer request command. */
  public void performAdditionalDomainTransferRequestLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      int years,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;

  /** Computes the expected update fee. */
  public BaseFee getUpdateFeeOrCredit(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput) throws EppException;

  /** Performs additional tasks required for an update command. */
  public void performAdditionalDomainUpdateLogic(
      DomainResource domain,
      String clientId,
      DateTime asOfDate,
      EppInput eppInput,
      HistoryEntry historyEntry) throws EppException;
}
