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

package google.registry.module.pubapi;

import static com.google.common.truth.Truth.assertThat;

import google.registry.request.Action;
import google.registry.request.RouterDisplayHelper;
import google.registry.testing.GoldenFileTestHelper;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PubApiRequestComponent}. */
class PubApiRequestComponentTest {

  @Test
  void testRoutingMap() {
    GoldenFileTestHelper.assertThatRoutesFromComponent(PubApiRequestComponent.class)
        .describedAs("pubapi routing map")
        .isEqualToGolden(PubApiRequestComponentTest.class, "pubapi_routing.txt");
  }

  @Test
  void testRoutingService() {
    assertThat(
            RouterDisplayHelper.extractHumanReadableRoutesWithWrongService(
                PubApiRequestComponent.class, Action.Service.PUBAPI))
        .isEmpty();
  }
}
