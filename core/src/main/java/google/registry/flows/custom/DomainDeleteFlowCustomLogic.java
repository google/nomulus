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
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainDeleteFlow;
import google.registry.model.domain.Domain;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.eppoutput.Result;
import google.registry.model.reporting.HistoryEntry;

/**
 * A no-op base class for {@link DomainDeleteFlow} custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainDeleteFlowCustomLogic extends BaseFlowCustomLogic {

  protected DomainDeleteFlowCustomLogic(
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
   * <p>This takes the {@link google.registry.model.eppoutput.Result.Code} and
   * {@link ResponseExtension}s as input and returns them, potentially with modifications.
   */
  @SuppressWarnings("unused")
  public BeforeResponseReturnData beforeResponse(BeforeResponseParameters parameters)
      throws EppException {
    return BeforeResponseReturnData.newBuilder()
        .setResultCode(parameters.resultCode())
        .setResponseExtensions(parameters.responseExtensions())
        .build();
  }

  /** A record to encapsulate parameters for a call to {@link #afterValidation}. */
  public record AfterValidationParameters(Domain existingDomain) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainDeleteFlowCustomLogic_AfterValidationParameters_Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setExistingDomain(Domain existingDomain);

      AfterValidationParameters build();
    }
  }

  /**
   * A record to encapsulate parameters for a call to {@link #beforeSave}.
   *
   * <p>Note that both newDomain and historyEntry are included in entityChanges. They are also
   * passed separately for convenience, but they are the same instance, and changes to them will
   * also affect what is persisted from entityChanges.
   */
  public record BeforeSaveParameters(
      Domain existingDomain,
      Domain newDomain,
      HistoryEntry historyEntry,
      EntityChanges entityChanges) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainDeleteFlowCustomLogic_BeforeSaveParameters_Builder();
    }

    /** Builder for {@link BeforeSaveParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setExistingDomain(Domain existingDomain);

      Builder setNewDomain(Domain newDomain);

      Builder setHistoryEntry(HistoryEntry historyEntry);

      Builder setEntityChanges(EntityChanges entityChanges);

      BeforeSaveParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #beforeResponse}. */
  public record BeforeResponseParameters(
      Result.Code resultCode, ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainDeleteFlowCustomLogic_BeforeResponseParameters_Builder();
    }

    /** Builder for {@link BeforeResponseParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setResultCode(Result.Code resultCode);

      Builder setResponseExtensions(ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseParameters build();
    }
  }

  /**
   * A record to encapsulate parameters for the return values from a call to {@link
   * #beforeResponse}.
   */
  public record BeforeResponseReturnData(
      Result.Code resultCode, ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainDeleteFlowCustomLogic_BeforeResponseReturnData_Builder();
    }

    /** Builder for {@link BeforeResponseReturnData}. */
    @AutoBuilder
    public interface Builder {

      Builder setResultCode(Result.Code resultCode);

      Builder setResponseExtensions(ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseReturnData build();
    }
  }
}
