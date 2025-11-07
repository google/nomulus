// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import google.registry.testing.GoldenFileTestHelper;
import google.registry.testing.TestDataHelper;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestComponent}. */
public class RequestComponentTest {

  @Test
  void testRoutingMap() {
    GoldenFileTestHelper.assertThatRoutesFromComponent(RequestComponent.class)
        .describedAs("routing map")
        .isEqualToGolden(RequestComponentTest.class, "routing.txt");
  }

  private Set<Route> getRoutes(Class<?> context, String filename) {
    return TestDataHelper.loadFile(context, filename)
        .trim()
        .lines()
        .skip(1) // Skip the headers
        .map(Route::create)
        .collect(Collectors.toSet());
  }

  private record Route(
      String service,
      String path,
      String clazz,
      String methods,
      String ok,
      String min,
      String userPolicy) {
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings().trimResults();

    static Route create(String line) {
      ImmutableList<String> parts = ImmutableList.copyOf(splitter.split(line));
      assertThat(parts.size()).isEqualTo(7);
      return new Route(
          parts.get(0),
          parts.get(1),
          parts.get(2),
          parts.get(3),
          parts.get(4),
          parts.get(5),
          parts.get(6));
    }
  }
}
