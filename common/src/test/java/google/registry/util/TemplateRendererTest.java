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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TemplateRenderer}. */
class TemplateRendererTest {

  private final TemplateRenderer renderer = new TemplateRenderer();

  @Test
  void testRender_success() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "name", "World", "score", 42, "showMessage", true, "message", "Keep going!");
    String result = renderer.render("google/registry/util/test_template.ftl", data);
    assertThat(result).isEqualTo("Hello World!\nYour score is 42.\nMessage: Keep going!\n");
  }

  @Test
  void testRender_conditional_false() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "User", "score", 0, "showMessage", false);
    String result = renderer.render("google/registry/util/test_template.ftl", data);
    assertThat(result).isEqualTo("Hello User!\nYour score is 0.\n");
  }

  @Test
  void testRender_htmlEscaping() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "<b>World</b>", "score", 42, "showMessage", false);
    String result = renderer.render("google/registry/util/test_template.ftl", data);
    assertThat(result).contains("Hello &lt;b&gt;World&lt;/b&gt;!");
  }

  @Test
  void testRender_missingTemplate_throwsException() {
    assertThrows(
        RuntimeException.class,
        () -> renderer.render("non/existent/template.ftl", ImmutableMap.of()));
  }

  @Test
  void testRender_missingVariable_throwsException() {
    // The template expects 'name', 'score', and 'showMessage', but the map is empty.
    assertThrows(
        RuntimeException.class,
        () -> renderer.render("google/registry/util/test_template.ftl", ImmutableMap.of()));
  }

  @Test
  void testRender_unusedVariable_ignored() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "name",
            "User",
            "score",
            100,
            "showMessage",
            false,
            "unusedKey",
            "This should be ignored");
    String result = renderer.render("google/registry/util/test_template.ftl", data);
    assertThat(result).isEqualTo("Hello User!\nYour score is 100.\n");
  }
}
