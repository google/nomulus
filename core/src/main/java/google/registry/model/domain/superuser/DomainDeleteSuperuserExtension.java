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

package google.registry.model.domain.superuser;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/** A superuser extension that may be present on domain delete commands. */
@XmlRootElement(name = "domainDelete")
public class DomainDeleteSuperuserExtension extends SuperuserExtension {

  @XmlElement(name = "redemptionGracePeriodDays")
  int redemptionGracePeriodDays;

  @XmlElement(name = "pendingDeleteDays")
  int pendingDeleteDays;

  public int getRedemptionGracePeriodDays() {
    return redemptionGracePeriodDays;
  }

  public int getPendingDeleteDays() {
    return pendingDeleteDays;
  }
}
