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

package google.registry.ui.server;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableSet;
import google.registry.request.Action;
import google.registry.request.JsonActionRunner;
import google.registry.ui.server.registrar.HtmlAction;
import google.registry.ui.server.registrar.JsonGetAction;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Test;

/** Unit tests of action membership. */
final class ActionMembershipTest {

  @Test
  void testAllActionsEitherHtmlOrJson() {
    // All UI actions should serve valid HTML or JSON. There are three valid options:
    // 1. Extending HtmlAction to signal that we are serving an HTML page
    // 2. Extending JsonAction to show that we are serving JSON POST requests
    // 3. Extending JsonGetAction to serve JSON GET requests
    ImmutableSet.Builder<String> failingClasses = new ImmutableSet.Builder<>();
    try (ScanResult scanResult =
        new ClassGraph().enableAnnotationInfo().whitelistPackages("google.registry.ui").scan()) {
      scanResult
          .getClassesWithAnnotation(Action.class.getName())
          .forEach(
              classInfo -> {
                if (!classInfo.extendsSuperclass(HtmlAction.class.getName())
                    && !classInfo.implementsInterface(JsonActionRunner.JsonAction.class.getName())
                    && !classInfo.implementsInterface(JsonGetAction.class.getName())) {
                  failingClasses.add(classInfo.getName());
                }
              });
    }
    assertWithMessage(
            "All UI actions must implement / extend HtmlAction, JsonGetAction, "
                + "or JsonActionRunner.JsonAction. Failing classes:")
        .that(failingClasses.build())
        .isEmpty();
  }
}
