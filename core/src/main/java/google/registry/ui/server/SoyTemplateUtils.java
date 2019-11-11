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

package google.registry.ui.server;

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.io.Resources.asCharSource;
import static com.google.common.io.Resources.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.SoyUtils;
import com.google.template.soy.parseinfo.SoyFileInfo;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.ui.ConsoleDebug;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Helper methods for rendering Soy templates from Java code. */
public final class SoyTemplateUtils {

  @VisibleForTesting
  public static final Supplier<SoyCssRenamingMap> CSS_RENAMING_MAP_SUPPLIER =
      SoyTemplateUtils.createCssRenamingMapSupplier(
          Resources.getResource("google/registry/ui/css/registrar_bin.css.js"),
          Resources.getResource("google/registry/ui/css/registrar_dbg.css.js"));

  /** Returns a memoized supplier containing compiled tofu. */
  public static Supplier<SoyTofu> createTofuSupplier(final SoyFileInfo... soyInfos) {
    return memoize(
        () -> {
          ConsoleDebug debugMode = ConsoleDebug.get();
          SoyFileSet.Builder builder = SoyFileSet.builder();
          for (SoyFileInfo soyInfo : soyInfos) {
            builder.add(getResource(soyInfo.getClass(), soyInfo.getFileName()));
          }
          Map<String, Object> globals;
          try {
            globals =
                new HashMap<>(SoyUtils.parseCompileTimeGlobals(asCharSource(SOY_GLOBALS, UTF_8)));
          } catch (IOException e) {
            throw new RuntimeException("Failed to load soy globals", e);
          }
          globals.put("DEBUG", debugMode.ordinal());
          builder.setCompileTimeGlobals(globals);
          return builder.build().compileToTofu();
        });
  }

  /** Returns a memoized supplier of the thing you pass to {@code setCssRenamingMap()}. */
  public static Supplier<SoyCssRenamingMap> createCssRenamingMapSupplier(
      final URL cssMap,
      final URL cssMapDebug) {
    return memoize(
        () -> {
          final ImmutableMap<String, String> renames = getCssRenames(cssMap, cssMapDebug);
          return (cssClassName) -> {
            List<String> result = new ArrayList<>();
            for (String part : CSS_CLASS_SPLITTER.split(cssClassName)) {
              result.add(renames.getOrDefault(part, part));
            }
            return CSS_CLASS_JOINER.join(result);
          };
        });
  }

  private static ImmutableMap<String, String> getCssRenames(URL cssMap, URL cssMapDebug) {
    try {
      switch (ConsoleDebug.get()) {
        case RAW:
          return ImmutableMap.of();  // See firstNonNull() above for clarification.
        case DEBUG:
          return extractCssRenames(Resources.toString(cssMapDebug, UTF_8));
        default:
          return extractCssRenames(Resources.toString(cssMap, UTF_8));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to load css map", e);
    }
  }

  /**
   * Extract class name rewrites from a {@code .css.js} mapping file.
   *
   * <p>This is the file created when you pass {@code --css_renaming_output_file} to the Closure
   * Stylesheets compiler. In order for this to work, {@code --output_renaming_map_format} should
   * be {@code CLOSURE_COMPILED} or {@code CLOSURE_UNCOMPILED}.
   *
   * <p>Here's an example of what the {@code .css.js} file looks like:<pre>
   *
   *   goog.setCssNameMapping({
   *     "nonLatin": "a",
   *     "secondary": "b",
   *     "mobile": "c"
   *   });</pre>
   *
   * <p>This is a burden that's only necessary for tofu, since the closure compiler is smart enough
   * to substitute CSS class names when soy is compiled to JavaScript.
   */
  private static ImmutableMap<String, String> extractCssRenames(String json) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    Matcher matcher = KEY_VALUE_PATTERN.matcher(json);
    while (matcher.find()) {
      builder.put(matcher.group(1), matcher.group(2));
    }
    return builder.build();
  }

  private static final URL SOY_GLOBALS = getResource("google/registry/ui/globals.txt");
  private static final Splitter CSS_CLASS_SPLITTER = Splitter.on('-');
  private static final Joiner CSS_CLASS_JOINER = Joiner.on('-');
  private static final Pattern KEY_VALUE_PATTERN =
      Pattern.compile("['\"]([^'\"]+)['\"]: ['\"]([^'\"]+)['\"]");

  private SoyTemplateUtils() {}
}
