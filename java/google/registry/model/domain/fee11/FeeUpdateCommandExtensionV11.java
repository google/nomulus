// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.domain.fee11;

import google.registry.model.domain.fee.FeeTransformCommandExtension;
import google.registry.model.domain.fee.FeeTransformCommandExtensionImpl;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

/** A fee extension that may be present on domain update commands. */
@XmlRootElement(name = "update")
@XmlType(propOrder = {"currency", "fees", "credits"})
public class FeeUpdateCommandExtensionV11
    extends FeeTransformCommandExtensionImpl implements FeeTransformCommandExtension {
  
  @Override
  public FeeTransformResponseExtension.Builder createResponseBuilder() {
    return new FeeUpdateResponseExtensionV11.Builder();
  }
}
