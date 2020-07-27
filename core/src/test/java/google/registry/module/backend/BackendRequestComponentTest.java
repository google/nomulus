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

package google.registry.module.backend;

import static com.google.common.truth.Truth.assertThat;

import google.registry.request.Action;
import google.registry.request.RouterDisplayHelper;
import google.registry.testing.GoldenFileTestHelper;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BackendRequestComponent}. */
class BackendRequestComponentTest {

  @Test
  void testRoutingMap() {
    GoldenFileTestHelper.assertThatRoutesFromComponent(BackendRequestComponent.class)
        .describedAs("backend routing map")
        .isEqualToGolden(BackendRequestComponentTest.class, "backend_routing.txt");
  }

  @Test
  void testRoutingService() {
    assertThat(
            RouterDisplayHelper.extractHumanReadableRoutesWithWrongService(
                BackendRequestComponent.class, Action.Service.BACKEND))
        .isEmpty();
  }
}
