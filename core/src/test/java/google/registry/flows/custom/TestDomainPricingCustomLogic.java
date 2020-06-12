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

import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.flows.domain.FeesAndCredits;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.eppinput.EppInput;
import java.math.BigDecimal;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/** A class to customize {@link DomainPricingLogic} for testing. */
public class TestDomainPricingCustomLogic extends DomainPricingCustomLogic {

  private static final BigDecimal ONE_HUNDRED_BUCKS = Money.of(CurrencyUnit.USD, 100).getAmount();

  protected TestDomainPricingCustomLogic(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    super(eppInput, sessionMetadata, flowMetadata);
  }

  @Override
  public FeesAndCredits customizeRenewPrice(RenewPriceParameters priceParameters) {
    return priceParameters.domainName().toString().startsWith("costly-renew")
        ? addCustomFee(
            priceParameters.feesAndCredits(), Fee.create(ONE_HUNDRED_BUCKS, FeeType.RENEW, true))
        : priceParameters.feesAndCredits();
  }

  @Override
  public FeesAndCredits customizeTransferPrice(TransferPriceParameters priceParameters) {
    return priceParameters.domainName().toString().startsWith("expensive")
        ? addCustomFee(
            priceParameters.feesAndCredits(), Fee.create(ONE_HUNDRED_BUCKS, FeeType.TRANSFER, true))
        : priceParameters.feesAndCredits();
  }

  @Override
  public FeesAndCredits customizeUpdatePrice(UpdatePriceParameters priceParameters) {
    return priceParameters.domainName().toString().startsWith("non-free-update")
        ? addCustomFee(
            priceParameters.feesAndCredits(), Fee.create(ONE_HUNDRED_BUCKS, FeeType.UPDATE, true))
        : priceParameters.feesAndCredits();
  }

  private static FeesAndCredits addCustomFee(FeesAndCredits feesAndCredits, BaseFee customFee) {
    return feesAndCredits
        .asBuilder()
        .setFeeExtensionRequired(true)
        .addFeeOrCredit(customFee)
        .build();
  }
}
