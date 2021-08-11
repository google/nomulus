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

import com.google.auto.value.AutoValue;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.flows.domain.FeesAndCredits;
import google.registry.model.ImmutableObject;
import google.registry.model.eppinput.EppInput;
import google.registry.model.tld.Registry;
import org.joda.time.DateTime;

/**
 * A no-op base class to customize {@link DomainPricingLogic}.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainPricingCustomLogic extends BaseFlowCustomLogic {

  protected DomainPricingCustomLogic(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
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
  public FeesAndCredits customizeRenewPrice(RenewPriceParameters priceParameters)
      throws EppException {
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

  /** A class to encapsulate parameters for a call to {@link #customizeCreatePrice} . */
  @AutoValue
  public abstract static class CreatePriceParameters extends ImmutableObject {

    public abstract FeesAndCredits feesAndCredits();

    public abstract Registry registry();

    public abstract InternetDomainName domainName();

    public abstract DateTime asOfDate();

    public abstract int years();

    public static Builder newBuilder() {
      return new AutoValue_DomainPricingCustomLogic_CreatePriceParameters.Builder();
    }

    /** Builder for {@link CreatePriceParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      public abstract Builder setRegistry(Registry registry);

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setAsOfDate(DateTime asOfDate);

      public abstract Builder setYears(int years);

      public abstract CreatePriceParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #customizeRenewPrice} . */
  @AutoValue
  public abstract static class RenewPriceParameters extends ImmutableObject {

    public abstract FeesAndCredits feesAndCredits();

    public abstract Registry registry();

    public abstract InternetDomainName domainName();

    public abstract DateTime asOfDate();

    public abstract int years();

    public static Builder newBuilder() {
      return new AutoValue_DomainPricingCustomLogic_RenewPriceParameters.Builder();
    }

    /** Builder for {@link RenewPriceParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      public abstract Builder setRegistry(Registry registry);

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setAsOfDate(DateTime asOfDate);

      public abstract Builder setYears(int years);

      public abstract RenewPriceParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #customizeRestorePrice} . */
  @AutoValue
  public abstract static class RestorePriceParameters extends ImmutableObject {

    public abstract FeesAndCredits feesAndCredits();

    public abstract Registry registry();

    public abstract InternetDomainName domainName();

    public abstract DateTime asOfDate();

    public static Builder newBuilder() {
      return new AutoValue_DomainPricingCustomLogic_RestorePriceParameters.Builder();
    }

    /** Builder for {@link RestorePriceParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      public abstract Builder setRegistry(Registry registry);

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setAsOfDate(DateTime asOfDate);

      public abstract RestorePriceParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #customizeTransferPrice} . */
  @AutoValue
  public abstract static class TransferPriceParameters extends ImmutableObject {

    public abstract FeesAndCredits feesAndCredits();

    public abstract Registry registry();

    public abstract InternetDomainName domainName();

    public abstract DateTime asOfDate();

    public static Builder newBuilder() {
      return new AutoValue_DomainPricingCustomLogic_TransferPriceParameters.Builder();
    }

    /** Builder for {@link TransferPriceParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      public abstract Builder setRegistry(Registry registry);

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setAsOfDate(DateTime asOfDate);

      public abstract TransferPriceParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #customizeUpdatePrice} . */
  @AutoValue
  public abstract static class UpdatePriceParameters extends ImmutableObject {

    public abstract FeesAndCredits feesAndCredits();

    public abstract Registry registry();

    public abstract InternetDomainName domainName();

    public abstract DateTime asOfDate();

    public static Builder newBuilder() {
      return new AutoValue_DomainPricingCustomLogic_UpdatePriceParameters.Builder();
    }

    /** Builder for {@link UpdatePriceParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setFeesAndCredits(FeesAndCredits feesAndCredits);

      public abstract Builder setRegistry(Registry registry);

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setAsOfDate(DateTime asOfDate);

      public abstract UpdatePriceParameters build();
    }
  }
}
