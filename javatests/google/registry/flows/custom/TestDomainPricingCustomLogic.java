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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.flows.domain.DomainPricingLogic.FeesAndCredits;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.Fee;
import google.registry.model.eppinput.EppInput;
import java.math.BigDecimal;
import java.util.List;

/** A class to customize {@link DomainPricingLogic} for testing. */
public class TestDomainPricingCustomLogic extends DomainPricingCustomLogic {

  protected TestDomainPricingCustomLogic(EppInput eppInput, SessionMetadata sessionMetadata) {
    super(eppInput, sessionMetadata);
  }

  private static BaseFee domainNameToFeeOrCredit(InternetDomainName domainName) {
    // The second-level domain should be of the form "description-price", where description is the
    // description string of the fee or credit, and price is the price (credit if negative, fee
    // otherwise). To make sure this is a valid domain name, don't use any spaces, and limit prices
    // to integers. Don't use a two-character description for credits, since it is illegal to have
    // both the third and fourth characters of a domain name label be hyphens.
    List<String> components =
        Splitter.on('-')
            .limit(2)
            .splitToList(Iterables.getFirst(Splitter.on('.').split(domainName.toString()), ""));
    checkArgument(components.size() == 2, "Domain name must be of the form description-price.tld");
    int price = Integer.parseInt(components.get(1));
    if (price < 0) {
      return Credit.create(
          new BigDecimal(price), FeeType.valueOf(Ascii.toUpperCase(components.get(0))));
    } else {
      return Fee.create(
          new BigDecimal(price), FeeType.valueOf(Ascii.toUpperCase(components.get(0))));
    }
  }

  /** A hook that customizes create price. */
  @Override
  public FeesAndCredits customizeCreatePrice(CreatePriceParameters createPriceParameters)
      throws EppException {
    InternetDomainName domainName = createPriceParameters.domainName();
    if (domainName.parent().toString().equals("flags")) {
      FeesAndCredits feesAndCredits = createPriceParameters.feesAndCredits();
      ImmutableList.Builder<BaseFee> baseFeeBuilder = new ImmutableList.Builder<>();
      baseFeeBuilder.addAll(feesAndCredits.getCredits());
      for (BaseFee fee : feesAndCredits.getFees()) {
        baseFeeBuilder.add(
            fee.getType() == FeeType.CREATE ? domainNameToFeeOrCredit(domainName) : fee);
      }
      return new FeesAndCredits(
          feesAndCredits.getCurrency(), Iterables.toArray(baseFeeBuilder.build(), BaseFee.class));
    } else {
      return createPriceParameters.feesAndCredits();
    }
  }
}
