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

import com.google.auto.value.AutoValue;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppinput.EppInput;
import google.registry.model.reporting.HistoryEntry;

/**
 * A no-op base class for {@link DomainCreateFlow} custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainCreateFlowCustomLogic extends BaseFlowCustomLogic {

  protected DomainCreateFlowCustomLogic(EppInput eppInput, SessionMetadata sessionMetadata) {
    super(eppInput, sessionMetadata);
  }

  /** A hook that runs at the end of the validation step to perform additional validation. */
  @SuppressWarnings("unused")
  public void afterValidation(AfterValidationParameters parameters) throws EppException {
    // Do nothing.
  }

  /**
   * A hook that runs before new entities are persisted, allowing them to be changed.
   *
   * <p>It returns the actual entity changes that should be persisted to Datastore. It is important
   * to be careful when changing the flow behavior for existing entities, because the core logic
   * across many different flows expects the existence of these entities and many of the fields on
   * them.
   */
  @SuppressWarnings("unused")
  public EntityChanges beforeSave(BeforeSaveParameters parameters) throws EppException {
    return parameters.entityChanges();
  }

  /** A class to encapsulate parameters for a call to {@link #afterValidation}. */
  @AutoValue
  public abstract static class AfterValidationParameters extends ImmutableObject {

    public abstract InternetDomainName domainName();

    public abstract int years();

    public static Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_AfterValidationParameters.Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setDomainName(InternetDomainName domainName);

      public abstract Builder setYears(int years);

      public abstract AfterValidationParameters build();
    }
  }

  /** A class to encapsulate parameters for a call to {@link #beforeSave}. */
  @AutoValue
  public abstract static class BeforeSaveParameters extends ImmutableObject {

    public abstract DomainResource newDomain();

    public abstract HistoryEntry historyEntry();

    public abstract EntityChanges entityChanges();

    public abstract int years();

    public static Builder newBuilder() {
      return new AutoValue_DomainCreateFlowCustomLogic_BeforeSaveParameters.Builder();
    }

    /** Builder for {@link BeforeSaveParameters}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setNewDomain(DomainResource newDomain);

      public abstract Builder setHistoryEntry(HistoryEntry historyEntry);

      public abstract Builder setEntityChanges(EntityChanges entityChanges);

      public abstract Builder setYears(int years);

      public abstract BeforeSaveParameters build();
    }
  }
}
