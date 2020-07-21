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

package google.registry.rde;

import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import google.registry.model.rde.RdeMode;
import java.util.EnumSet;

/** Types of objects that get embedded in an escrow deposit. */
public enum RdeResourceType {
  CONTACT("urn:ietf:params:xml:ns:rdeContact-1.0", EnumSet.of(FULL)),
  DOMAIN("urn:ietf:params:xml:ns:rdeDomain-1.0", EnumSet.of(FULL, THIN)),
  HOST("urn:ietf:params:xml:ns:rdeHost-1.0", EnumSet.of(FULL)),
  REGISTRAR("urn:ietf:params:xml:ns:rdeRegistrar-1.0", EnumSet.of(FULL, THIN)),
  IDN("urn:ietf:params:xml:ns:rdeIDN-1.0", EnumSet.of(FULL)),
  HEADER("urn:ietf:params:xml:ns:rdeHeader-1.0", EnumSet.of(FULL, THIN));

  private final String uri;
  private final ImmutableSet<RdeMode> modes;

  RdeResourceType(String uri, EnumSet<RdeMode> modes) {
    this.uri = uri;
    this.modes = ImmutableSet.copyOf(modes);
  }

  /** Returns RDE XML schema URI specifying resource. */
  public String getUri() {
    return uri;
  }

  /** Returns set indicating if resource is stored in BRDA thin deposits. */
  public ImmutableSet<RdeMode> getModes() {
    return modes;
  }

  /** Returns set of resource type URIs included in a deposit {@code mode}. */
  public static ImmutableSortedSet<String> getUris(RdeMode mode) {
    ImmutableSortedSet.Builder<String> builder =
        new ImmutableSortedSet.Builder<>(Ordering.natural());
    for (RdeResourceType resourceType : RdeResourceType.values()) {
      if (resourceType.getModes().contains(mode)) {
        builder.add(resourceType.getUri());
      }
    }
    return builder.build();
  }
}
