// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableMap;

/**
 * Main class of the Prober, which constructs the ProbingSequences then runs them
 */
public class Prober {

  //TODO: Create ImmutableMap between port numbers and protocols with Dagger
  public static final ImmutableMap<Integer, Protocol> portToProtocolMap = ImmutableMap.of();

  //TODO: Create and run probing sequences
  public static void main(String[] args) {
  }
}
