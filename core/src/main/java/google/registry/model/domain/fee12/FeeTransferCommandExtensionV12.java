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

package google.registry.model.domain.fee12;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.FeeTransferCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.List;

/** A fee extension that may be present on domain transfer requests. */
@XmlRootElement(name = "transfer")
@XmlType(propOrder = {"currency", "fees", "credits"})
public class FeeTransferCommandExtensionV12 extends FeeTransferCommandExtension {

  @XmlElement(name = "credit")
  List<Credit> credits;

  @Override
  public ImmutableList<Credit> getCredits() {
    return nullToEmptyImmutableCopy(credits);
  }

  @Override
  public FeeTransformResponseExtension.Builder createResponseBuilder() {
    return new FeeTransformResponseExtension.Builder(new FeeTransferResponseExtensionV12());
  }
}
