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

import com.google.common.collect.ImmutableMap;
import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import jakarta.inject.Inject;
import java.io.StringWriter;

/**
 * A utility class for rendering FreeMarker templates.
 *
 * <p>This renderer is configured to use HTML as the default output format, which enables automatic
 * escaping of all interpolated variables. It also uses the "computer" number format to ensure
 * consistent formatting of numeric values across different locales.
 */
public class TemplateRenderer {

  private final Configuration configuration;

  @Inject
  public TemplateRenderer() {
    this.configuration = new Configuration(Configuration.VERSION_2_3_32);
    this.configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "");
    this.configuration.setDefaultEncoding("UTF-8");
    this.configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    this.configuration.setLogTemplateExceptions(false);
    this.configuration.setWrapUncheckedExceptions(true);
    this.configuration.setFallbackOnNullLoopVariable(false);
    this.configuration.setOutputFormat(HTMLOutputFormat.INSTANCE);
    this.configuration.setNumberFormat("computer");
  }

  /**
   * Renders the specified template with the given data model.
   *
   * @param templatePath the path to the template file relative to the classpath root
   * @param dataModel an immutable map containing the data to be used in the template
   * @return the rendered template as a string
   * @throws RuntimeException if the template cannot be found, parsed, or processed
   */
  public String render(String templatePath, ImmutableMap<String, Object> dataModel) {
    try {
      Template template = configuration.getTemplate(templatePath);
      StringWriter writer = new StringWriter();
      template.process(dataModel, writer);
      return writer.toString();
    } catch (Exception e) {
      throw new RuntimeException(String.format("Error rendering template %s", templatePath), e);
    }
  }
}
