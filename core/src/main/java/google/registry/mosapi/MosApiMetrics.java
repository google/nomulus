// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mosapi;

import com.google.common.flogger.FluentLogger;
import google.registry.mosapi.MosApiModels.TldServiceState;
import jakarta.inject.Inject;
import java.util.List;

/** Metrics Exporter for MoSAPI. */
public class MosApiMetrics {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  public MosApiMetrics() {}

  public void recordStates(List<TldServiceState> states) {
    // b/467541269: Logic to push status to Cloud Monitoring goes here
    logger.atInfo().log("MoSAPI record metrics logic will be implemented from here");
  }
}
