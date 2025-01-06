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

package google.registry.rdap;

import com.google.common.base.Ascii;
import com.google.common.collect.Streams;
import google.registry.model.registrar.Registrar;
import java.util.Objects;
import java.util.Optional;

/** Utility functions for RDAP. */
public final class RdapUtils {

  private RdapUtils() {}

  /** Looks up a registrar by its IANA identifier. */
  static Optional<Registrar> getRegistrarByIanaIdentifier(final long ianaIdentifier) {
    return Streams.stream(Registrar.loadAllCached())
        .filter(registrar -> Objects.equals(ianaIdentifier, registrar.getIanaIdentifier()))
        .findFirst();
  }

  /**
   * Looks up a registrar by its name.
   *
   * <p>Used for RDAP Technical Implementation Guide 2.4.2 - search of registrar by the fn element.
   *
   * <p>For convenience, we use case insensitive search.
   */
  static Optional<Registrar> getRegistrarByName(String registrarName) {
    return Streams.stream(Registrar.loadAllCached())
        .filter(registrar -> Ascii.equalsIgnoreCase(registrarName, registrar.getRegistrarName()))
        .findFirst();
  }
}
