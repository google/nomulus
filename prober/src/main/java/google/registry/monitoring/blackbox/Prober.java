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

package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.blackbox.ProberModule.ProberComponent;

/**
 * Main class of the Prober, which obtains and starts the {@link ProbingSequence}s provided by
 * Dagger.
 */
public class Prober {

  /** Main Dagger Component */
  private static ProberComponent proberComponent =
      DaggerProberModule_ProberComponent.builder().build();

  public static void main(String[] args) {

    // Obtains WebWhois Sequence provided by proberComponent
    ImmutableSet<ProbingSequence> sequences = ImmutableSet.copyOf(proberComponent.sequences());

    // Tells Sequences to start running
    for (ProbingSequence sequence : sequences) {
      sequence.start();
    }
  }
}
