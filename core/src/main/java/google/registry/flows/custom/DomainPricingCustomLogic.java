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

import com.google.auto.value.AutoBuilder;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.flows.domain.FeesAndCredits;
import google.registry.model.eppinput.EppInput;
import google.registry.model.tld.Tld;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * A no-op base class to customize {@link DomainPricingLogic}.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainPricingCustomLogic extends BaseFlowCustomLogic {

  public DomainPricingCustomLogic(
      @Nullable EppInput eppInput,
      @Nullable SessionMetadata sessionMetadata,
      @Nullable FlowMetadata flowMetadata) {
    super(eppInput, sessionMetadata, flowMetadata);
  }

  /** A hook that customizes the create price. */
  @SuppressWarnings("unused")
  public FeesAndCredits customizeCreatePrice(CreatePriceParameters priceParameters)
      throws EppException {
    return priceParameters.feesAndCredits();
  }

  /** A hook that customizes the renew price. */
  @SuppressWarnings("unused")
  public FeesAndCredits customizeRenewPrice(RenewPriceParameters priceParameters) {
    return priceParameters.feesAndCredits();
  }

  /** A hook that customizes the restore price. */
  @SuppressWarnings("unused")
  public FeesAndCredits customizeRestorePrice(RestorePriceParameters priceParameters)
      throws EppException {
    return priceParameters.feesAndCredits();
  }

  /** A hook that customizes the transfer price. */
  @SuppressWarnings("unused")
  public FeesAndCredits customizeTransferPrice(TransferPriceParameters priceParameters)
      throws EppException {
    return priceParameters.feesAndCredits();
  }

  /** A hook that customizes the update price. */
  @SuppressWarnings("unused")
  public FeesAndCredits customizeUpdatePrice(UpdatePriceParameters priceParameters)
      throws EppException {
    return priceParameters.feesAndCredits();
  }

  /** A record to encapsulate parameters for a call to {@link #customizeCreatePrice} . */
  public record CreatePriceParameters(
      FeesAndCredits feesAndCredits,
      Tld tld,
      InternetDomainName domainName,
      DateTime asOfDate,
      int years) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainPricingCustomLogic_CreatePriceParameters_Builder();
    }

    /** Builder for {@link CreatePriceParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      Builder setTld(Tld tld);

      Builder setDomainName(InternetDomainName domainName);

      Builder setAsOfDate(DateTime asOfDate);

      Builder setYears(int years);

      CreatePriceParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #customizeRenewPrice} . */
  public record RenewPriceParameters(
      FeesAndCredits feesAndCredits,
      Tld tld,
      InternetDomainName domainName,
      DateTime asOfDate,
      int years) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainPricingCustomLogic_RenewPriceParameters_Builder();
    }

    /** Builder for {@link RenewPriceParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      Builder setTld(Tld tld);

      Builder setDomainName(InternetDomainName domainName);

      Builder setAsOfDate(DateTime asOfDate);

      Builder setYears(int years);

      RenewPriceParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #customizeRestorePrice} . */
  public record RestorePriceParameters(
      FeesAndCredits feesAndCredits, Tld tld, InternetDomainName domainName, DateTime asOfDate) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainPricingCustomLogic_RestorePriceParameters_Builder();
    }

    /** Builder for {@link RestorePriceParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      Builder setTld(Tld tld);

      Builder setDomainName(InternetDomainName domainName);

      Builder setAsOfDate(DateTime asOfDate);

      RestorePriceParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #customizeTransferPrice} . */
  public record TransferPriceParameters(
      FeesAndCredits feesAndCredits, Tld tld, InternetDomainName domainName, DateTime asOfDate) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainPricingCustomLogic_TransferPriceParameters_Builder();
    }

    /** Builder for {@link TransferPriceParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      Builder setTld(Tld tld);

      Builder setDomainName(InternetDomainName domainName);

      Builder setAsOfDate(DateTime asOfDate);

      TransferPriceParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #customizeUpdatePrice} . */
  public record UpdatePriceParameters(
      FeesAndCredits feesAndCredits, Tld tld, InternetDomainName domainName, DateTime asOfDate) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainPricingCustomLogic_UpdatePriceParameters_Builder();
    }

    /** Builder for {@link UpdatePriceParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      Builder setTld(Tld tld);

      Builder setDomainName(InternetDomainName domainName);

      Builder setAsOfDate(DateTime asOfDate);

      UpdatePriceParameters build();
    }
  }
}
