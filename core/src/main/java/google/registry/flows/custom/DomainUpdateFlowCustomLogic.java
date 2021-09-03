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
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainUpdateFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppinput.EppInput;
import google.registry.model.reporting.HistoryEntry;

/**
 * A no-op base class for {@link DomainUpdateFlow} custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainUpdateFlowCustomLogic extends BaseFlowCustomLogic {

  protected DomainUpdateFlowCustomLogic(
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

  /** A class to encapsulate parameters for a call to {@link #afterValidation}. */
  @AutoValue
  public abstract static class AfterValidationParameters extends ImmutableObject {

    public abstract DomainBase existingDomain();

    public static Builder newBuilder() {
      return new AutoValue_DomainUpdateFlowCustomLogic_AfterValidationParameters.Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setExistingDomain(DomainBase existingDomain);

      public abstract AfterValidationParameters build();
    }
  }

  /**
   * A class to encapsulate parameters for a call to {@link #beforeSave}.
   *
   * <p>Note that both newDomain and historyEntry are included in entityChanges. They are also
   * passed separately for convenience, but they are the same instance, and changes to them will
   * also affect what is persisted from entityChanges.
   */
  @AutoValue
  public abstract static class BeforeSaveParameters extends ImmutableObject {

    public abstract DomainBase existingDomain();

    public abstract DomainBase newDomain();

    public abstract HistoryEntry historyEntry();

    public abstract EntityChanges entityChanges();

    public static Builder newBuilder() {
      return new AutoValue_DomainUpdateFlowCustomLogic_BeforeSaveParameters.Builder();
    }

    /** Builder for {@link BeforeSaveParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setExistingDomain(DomainBase existingDomain);

      public abstract Builder setNewDomain(DomainBase newDomain);

      public abstract Builder setHistoryEntry(HistoryEntry historyEntry);

      public abstract Builder setEntityChanges(EntityChanges entityChanges);

      public abstract BeforeSaveParameters build();
    }
  }
}
