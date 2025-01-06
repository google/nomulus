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

package google.registry.tools;

import static google.registry.util.ResourceUtils.readResourceBytes;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import google.registry.tools.params.PathParameter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import javax.annotation.Nullable;

/** Parameter delegate class to handle logging configuration for {@link RegistryCli}. */
@Parameters(separators = " =")
final class LoggingParameters {

  @Nullable
  @Parameter(
      names = "--log_level",
      description = "Default level at which to log messages")
  private Level logLevel;

  @Parameter(
      names = "--logging_configs",
      description = "Comma-delimited list of logging properties to add to the logging.properties "
          + "file, e.g. com.example.level=WARNING,com.example.FooClass.level=SEVERE")
  private List<String> configLines = new ArrayList<>();

  @Nullable
  @Parameter(
      names = "--logging_properties_file",
      description = "File from which to read custom logging properties",
      validateWith = PathParameter.InputFile.class)
  private Path configFile;

  private static final ByteSource DEFAULT_LOG_CONFIG =
      readResourceBytes(LoggingParameters.class, "logging.properties");

  void configureLogging() throws IOException {
    ByteSource baseConfig = (configFile != null)
        ? Files.asByteSource(configFile.toFile())
        : DEFAULT_LOG_CONFIG;
    if (logLevel != null) {
      configLines.add(".level = " + logLevel);
    }
    // Add an extra leading newline in case base properties file does not end in a newline.
    String customProperties = "\n" + Joiner.on('\n').join(configLines);
    ByteSource logConfig =
        ByteSource.concat(baseConfig, ByteSource.wrap(customProperties.getBytes(UTF_8)));
    try (InputStream input = logConfig.openStream()) {
      LogManager.getLogManager().readConfiguration(input);
    }
  }
}
