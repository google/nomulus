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

package google.registry.model.domain.fee06;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.fee.FeeUpdateCommandExtension;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/** A fee extension that may be present on domain update commands. */
@XmlRootElement(name = "update")
@XmlType(propOrder = {"currency", "fees"})
public class FeeUpdateCommandExtensionV06 extends FeeUpdateCommandExtension {

  @Override
  public FeeTransformResponseExtension.Builder createResponseBuilder() {
    return new FeeTransformResponseExtension.Builder(new FeeUpdateResponseExtensionV06());
  }

  /** This version of the extension doesn't support the "credit" field. */
  @Override
  public ImmutableList<Credit> getCredits() {
    return ImmutableList.of();
  }
}
