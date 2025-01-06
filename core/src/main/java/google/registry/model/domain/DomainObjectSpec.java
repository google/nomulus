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

package google.registry.model.domain;

import google.registry.model.ImmutableObject;

/**
 * Specification of a domain by name, as used in version 0.11 fee check response items and version
 * 0.12 fee check commands. This class is used by JAXB, although it turns out that no JAXB-specific
 * annotations are necessary.
 */
public class DomainObjectSpec extends ImmutableObject {

  final String name;

  public String getName() {
    return name;
  }

  public DomainObjectSpec(String name) {
    this.name = name;
  }
}
