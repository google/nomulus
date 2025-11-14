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

package google.registry.module;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;

/**
 * Servlet used in the test server to handle routing.
 *
 * <p>This functions somewhat as a mock, because in the production environment our routing is
 * handled through the Kubernetes configuration (in the jetty package). Here, we can manually
 * configure routes for the test server when we need them.
 */
public class TestServlet extends ServletBase {
  private static final TestRegistryComponent component = DaggerTestRegistryComponent.create();
  private static final TestRequestHandler requestHandler = component.requestHandler();
  private static final Lazy<MetricReporter> metricReporter = component.metricReporter();

  public TestServlet() {
    super(requestHandler, metricReporter);
  }
}
