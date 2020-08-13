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

package google.registry.model.domain.superuser;

import google.registry.model.domain.Period;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/** A superuser extension that may be present on domain transfer request commands. */
@XmlRootElement(name = "domainTransferRequest")
public class DomainTransferRequestSuperuserExtension extends SuperuserExtension {

  // We need to specify the period here because the transfer object's period cannot be set to zero.
  @XmlElement(name = "renewalPeriod")
  Period renewalPeriod;

  // The number of days before the transfer will be automatically approved by the server. A value of
  // zero means the transfer will happen immediately.
  @XmlElement(name = "automaticTransferLength")
  int automaticTransferLength;

  public Period getRenewalPeriod() {
    return renewalPeriod;
  }

  public int getAutomaticTransferLength() {
    return automaticTransferLength;
  }
}
