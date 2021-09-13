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
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.reporting.HistoryEntry;
import java.util.Optional;

/**
 * A no-op base class for {@link DomainCreateFlow} custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainCreateFlowCustomLogic extends BaseFlowCustomLogic {

  protected DomainCreateFlowCustomLogic(
      EppInput eppInput, SessionMetadata sessionMetadata, FlowMetadata flowMetadata) {
    super(eppInput, sessionMetadata, flowMetadata);
  }

  /** A hook that runs before any validation. This is useful to e.g. add allowable extensions. */
  @SuppressWarnings("unused")
  public void beforeValidation() throws EppException {
    // Do nothing.
  }

  /** A hook that runs at the end of the validation step to perform additional validation. */
  @SuppressWarnings("unused")
  public void afterValidation(AfterValidationParameters parameters) throws EppException {
    // Do nothing.
  }

  /**
   * A hook that runs before new entities are persisted, allowing them to be changed.
   *
   * <p>It returns the actual entity changes that should be persisted to the database. It is
   * important to be careful when changing the flow behavior for existing entities, because the core
   * logic across many different flows expects the existence of these entities and many of the
   * fields on them.
   */
  @SuppressWarnings("unused")
  public EntityChanges beforeSave(BeforeSaveParameters parameters) throws EppException {
    return parameters.entityChanges();
  }

  /**
   * A hook that runs before the response is returned.
   *
   * <p>This takes the {@link ResponseData} and {@link ResponseExtension}s as input and returns
   * them, potentially with modifications.
   */
  @SuppressWarnings("unused")
  public BeforeResponseReturnData beforeResponse(BeforeResponseParameters parameters)
      throws EppException {
    return BeforeResponseReturnData.newBuilder()
        .setResData(parameters.resData())
        .setResponseExtensions(parameters.responseExtensions())
        .build();
  }

  /** A class to encapsulate parameters for a call to {@link #afterValidation}. */
  @AutoValue
  public abstract static class AfterValidationParameters extends ImmutableObject {

    /** The parsed domain name of the domain that is requested to be created. */
    public abstract InternetDomainName domainName();

    /**
     * The number of years that the domain name will be registered for.
     *
     * <p>On standard TLDs, this is usually 1.
     */
    public abstract int years();

    /**
     * The ID of the validated signed mark.
     *
     * <p>If a signed mark was not supplied, this value will be absent.
     */
    public abstract Optional<String> signedMarkId();

    public static Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_AfterValidationParameters.Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setYears(int years);

      public abstract Builder setSignedMarkId(Optional<String> signedMarkId);

      public abstract AfterValidationParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #beforeSave}. */
  @AutoValue
  public abstract static class BeforeSaveParameters extends ImmutableObject {

    /**
     * The new {@link DomainBase} entity that is going to be persisted at the end of the
     * transaction.
     */
    public abstract DomainBase newDomain();

    /**
     * The new {@link HistoryEntry} entity for the domain's creation that is going to be persisted
     * at the end of the transaction.
     */
    public abstract HistoryEntry historyEntry();

    /**
     * The collection of {@link EntityChanges} (including new entities and those to delete) that
     * will be persisted at the end of the transaction.
     *
     * <p>Note that the new domain and history entry are also included as saves in this collection,
     * and are separated out above solely for convenience, as they are most likely to need to be
     * changed. Removing them from the collection will cause them not to be saved, which is most
     * likely not what you intended.
     */
    public abstract EntityChanges entityChanges();

    /**
     * The number of years that the domain name will be registered for.
     *
     * <p>On standard TLDs, this is usually 1.
     */
    public abstract int years();

    public static Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_BeforeSaveParameters.Builder();
    }

    /** Builder for {@link BeforeSaveParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setNewDomain(DomainBase newDomain);

      public abstract Builder setHistoryEntry(HistoryEntry historyEntry);

      public abstract Builder setEntityChanges(EntityChanges entityChanges);

      public abstract Builder setYears(int years);

      public abstract BeforeSaveParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #beforeResponse}. */
  @AutoValue
  public abstract static class BeforeResponseParameters extends ImmutableObject {

    public abstract ResponseData resData();

    public abstract ImmutableList<? extends ResponseExtension> responseExtensions();

    public static BeforeResponseParameters.Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_BeforeResponseParameters.Builder();
    }

    /** Builder for {@link DomainCreateFlowCustomLogic.BeforeResponseParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract BeforeResponseParameters.Builder setResData(ResponseData resData);

      public abstract BeforeResponseParameters.Builder setResponseExtensions(
          ImmutableList<? extends ResponseExtension> responseExtensions);

      public abstract BeforeResponseParameters build();
    }
  }

  /**
   * A class to encapsulate parameters for the return values from a call to {@link #beforeResponse}.
   */
  @AutoValue
  public abstract static class BeforeResponseReturnData extends ImmutableObject {

    public abstract ResponseData resData();

    public abstract ImmutableList<? extends ResponseExtension> responseExtensions();

    public static BeforeResponseReturnData.Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_BeforeResponseReturnData.Builder();
    }

    /** Builder for {@link DomainCreateFlowCustomLogic.BeforeResponseReturnData}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract BeforeResponseReturnData.Builder setResData(ResponseData resData);

      public abstract BeforeResponseReturnData.Builder setResponseExtensions(
          ImmutableList<? extends ResponseExtension> responseExtensions);

      public abstract BeforeResponseReturnData build();
    }
  }
}
