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

package google.registry.tools.params;

import google.registry.model.EppResource;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;

/** Enum to make it easy for a command to accept a flag that specifies an EppResource subclass. */
public enum EppResourceTypeParameter {
  CONTACT(Contact.class),
  DOMAIN(Domain.class),
  HOST(Host.class);

  private final Class<? extends EppResource> type;

  EppResourceTypeParameter(Class<? extends EppResource> type) {
    this.type = type;
  }

  public Class<? extends EppResource> getType() {
    return type;
  }
}
