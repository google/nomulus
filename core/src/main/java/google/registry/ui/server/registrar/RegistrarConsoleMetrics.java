// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import javax.inject.Inject;

final class RegistrarConsoleMetrics {

  private static final ImmutableSet<LabelDescriptor> CONSOLE_LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("clientId", "target registrar client ID"),
          LabelDescriptor.create("explicitClientId", "whether the client ID is set explicitly"),
          LabelDescriptor.create("role", "Role[s] of the user making the request"),
          LabelDescriptor.create("status", "whether the request is successful"));

  static final IncrementableMetric consoleRequestMetric =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/console/registrar/console_requests",
              "Count of /registrar requests",
              "count",
              CONSOLE_LABEL_DESCRIPTORS);

  private static final ImmutableSet<LabelDescriptor> SETTINGS_LABEL_DESCRIPTORS =
      ImmutableSet.of(
          LabelDescriptor.create("clientId", "target registrar client ID"),
          LabelDescriptor.create("action", "action performed"),
          LabelDescriptor.create("role", "Role[s] of the user making the request"),
          LabelDescriptor.create("status", "whether the request is successful"));

  static final IncrementableMetric settingsRequestMetric =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/console/registrar/setting_requests",
              "Count of /registrar-settings requests",
              "count",
              SETTINGS_LABEL_DESCRIPTORS);

  @Inject
  public RegistrarConsoleMetrics() {}

  void registerConsoleRequest(
      String clientId, boolean explicitClientId, ImmutableSet<Role> roles, String status) {
    consoleRequestMetric.increment(
        clientId, String.valueOf(explicitClientId), String.valueOf(roles), status);
  }

  void registerSettingsRequest(
      String clientId, String action, ImmutableSet<Role> roles, String status) {
    settingsRequestMetric.increment(clientId, action, String.valueOf(roles), status);
  }
}
