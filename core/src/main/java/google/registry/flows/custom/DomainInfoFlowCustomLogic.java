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
import google.registry.flows.domain.DomainInfoFlow;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainInfoData;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;

/**
 * A no-op base class for {@link DomainInfoFlow} custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class DomainInfoFlowCustomLogic extends BaseFlowCustomLogic {

  protected DomainInfoFlowCustomLogic(
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
   * A hook that runs before the response is returned.
   *
   * <p>This takes the {@link Domain} and {@link ResponseExtension}s as input and returns them,
   * potentially with modifications.
   */
  @SuppressWarnings("unused")
  public BeforeResponseReturnData beforeResponse(BeforeResponseParameters parameters)
      throws EppException {
    return BeforeResponseReturnData.newBuilder()
        .setResData(parameters.resData())
        .setResponseExtensions(parameters.responseExtensions())
        .build();
  }

  /** A record to encapsulate parameters for a call to {@link #afterValidation}. */
  public record AfterValidationParameters(Domain domain) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainInfoFlowCustomLogic_AfterValidationParameters_Builder();
    }

    /** Builder for {@link AfterValidationParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setDomain(Domain domain);

      AfterValidationParameters build();
    }
  }

  /** A record to encapsulate parameters for a call to {@link #beforeResponse}. */
  public record BeforeResponseParameters(
      Domain domain,
      DomainInfoData resData,
      ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainInfoFlowCustomLogic_BeforeResponseParameters_Builder();
    }

    /** Builder for {@link BeforeResponseParameters}. */
    @AutoBuilder
    public interface Builder {

      Builder setDomain(Domain domain);

      Builder setResData(DomainInfoData resData);

      Builder setResponseExtensions(ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseParameters build();
    }
  }

  /**
   * A record to encapsulate parameters for the return values from a call to {@link
   * #beforeResponse}.
   */
  public record BeforeResponseReturnData(
      DomainInfoData resData, ImmutableList<? extends ResponseExtension> responseExtensions) {

    public static Builder newBuilder() {
      return new AutoBuilder_DomainInfoFlowCustomLogic_BeforeResponseReturnData_Builder();
    }

    /** Builder for {@link BeforeResponseReturnData}. */
    @AutoBuilder
    public interface Builder {

      Builder setResData(DomainInfoData resData);

      Builder setResponseExtensions(ImmutableList<? extends ResponseExtension> responseExtensions);

      BeforeResponseReturnData build();
    }
  }
}
