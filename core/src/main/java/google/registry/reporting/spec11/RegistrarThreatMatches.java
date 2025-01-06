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

package google.registry.reporting.spec11;

import com.google.common.collect.ImmutableList;
import google.registry.beam.spec11.ThreatMatch;
import java.util.List;

/** Value record representing the registrar and list-of-threat-matches pair stored in GCS. */
public record RegistrarThreatMatches(String clientId, ImmutableList<ThreatMatch> threatMatches) {

  static RegistrarThreatMatches create(String clientId, List<ThreatMatch> threatMatches) {
    return new RegistrarThreatMatches(clientId, ImmutableList.copyOf(threatMatches));
  }
}
