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
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.model.domain.Domain;
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

  /**
   * A record to encapsulate parameters for a call to {@link #afterValidation}.
   *
   * @param domainName The parsed domain name of the domain that is requested to be created.
   * @param years The number of years that the domain name will be registered for (typically 1).
   * @param signedMarkId The ID of the validated signed mark, or absent if not supplied.
   */
  public record AfterValidationParameters(
      InternetDomainName domainName, int years, Optional<String> signedMarkId) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainCreateFlowCustomLogic_AfterValidationParameters_Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setDomainName(InternetDomainName domainName);

      Builder setYears(int years);

      Builder setSignedMarkId(Optional<String> signedMarkId);

      AfterValidationParameters build();
    }
  }

  /**
   * A record to encapsulate parameters for a call to {@link #beforeSave}.
   *
   * @param newDomain The new {@link Domain} entity that is going to be persisted at the end of the
   *     transaction.
   * @param historyEntry The new {@link HistoryEntry} entity for the domain's creation that is going
   *     to be persisted at the end of the transaction.
   * @param entityChanges The collection of {@link EntityChanges} (including new entities and those
   *     to delete) that will be persisted at the end of the transaction.
   *     <p>Note that the new domain and history entry are also included as saves in this
   *     collection, and are separated out above solely for convenience, as they are most likely to
   *     need to be changed. Removing them from the collection will cause them not to be saved,
   *     which is most likely not what you intended.
   * @param years The number of years that the domain name will be registered for (typically 1).
   */
  public record BeforeSaveParameters(
      Domain newDomain, HistoryEntry historyEntry, EntityChanges entityChanges, int years) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainCreateFlowCustomLogic_BeforeSaveParameters_Builder();
    }

    /** Builder for {@link BeforeSaveParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setNewDomain(Domain newDomain);

      Builder setHistoryEntry(HistoryEntry historyEntry);

      Builder setEntityChanges(EntityChanges entityChanges);

      Builder setYears(int years);

      BeforeSaveParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #beforeResponse}. */
  public record BeforeResponseParameters(
      ResponseData resData, ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static BeforeResponseParameters.Builder newBuilder() {
      return new AutoBuilder_DomainCreateFlowCustomLogic_BeforeResponseParameters_Builder();
    }

    /** Builder for {@link DomainCreateFlowCustomLogic.BeforeResponseParameters}. */
    @AutoBuilder
    public interface Builder {

      BeforeResponseParameters.Builder setResData(ResponseData resData);

      BeforeResponseParameters.Builder setResponseExtensions(
          ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseParameters build();
    }
  }

  /**
   * A record to encapsulate parameters for the return values from a call to {@link
   * #beforeResponse}.
   */
  public record BeforeResponseReturnData(
      ResponseData resData, ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static BeforeResponseReturnData.Builder newBuilder() {
      return new AutoBuilder_DomainCreateFlowCustomLogic_BeforeResponseReturnData_Builder();
    }

    /** Builder for {@link DomainCreateFlowCustomLogic.BeforeResponseReturnData}. */
    @AutoBuilder
    public interface Builder {

      BeforeResponseReturnData.Builder setResData(ResponseData resData);

      BeforeResponseReturnData.Builder setResponseExtensions(
          ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseReturnData build();
    }
  }
}
