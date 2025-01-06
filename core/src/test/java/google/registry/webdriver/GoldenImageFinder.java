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

package google.registry.webdriver;

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.hash.Hashing.sha256;
import static com.google.common.io.MoreFiles.asByteSource;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Maps;
import com.google.common.io.MoreFiles;
import google.registry.tools.params.ParameterFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/** A tool to find the new golden image by selecting the screenshot which appears the most times. */
@Parameters(
    separators = " =",
    commandDescription = "Find the new golden images from the given screenshots.")
public class GoldenImageFinder {

  @Parameter(
      names = {"--screenshots_for_goldens_dir"},
      description = "Directory to store screenshots generated as candidate of golden images.",
      required = true)
  private Path screenshotsForGoldensDir;

  @Parameter(
      names = {"--new_goldens_dir"},
      description = "Directory to store the new golden images selected by this application.",
      required = true)
  private Path newGoldensDir;

  @Parameter(
      names = {"--existing_goldens_dir"},
      description = "Directory to store the existing golden images.",
      required = true)
  private Path existingGoldensDir;

  @Parameter(
      names = {"--override_existing_goldens"},
      description =
          "If set to true, the new golden images are copied to override the existing ones.")
  private boolean overrideExistingGoldens = false;

  private void run() {
    try (Stream<Path> allScreenshots =
        Files.find(screenshotsForGoldensDir, 3, (file, attr) -> attr.isRegularFile())) {
      if (newGoldensDir.toFile().isDirectory()) {
        MoreFiles.deleteRecursively(newGoldensDir);
      }
      newGoldensDir.toFile().mkdirs();

      allScreenshots
          .collect(
              toImmutableSetMultimap(
                  imagePath -> imagePath.toFile().getName(), imagePath -> imagePath))
          .asMap()
          .forEach(
              (imageFileName, screenshots) -> {
                Map<String, Integer> occurrenceByHash = Maps.newHashMap();
                int currNumOccurrence = 0;
                Path currImagePath = null;
                for (Path screenshot : screenshots) {
                  String imageHash;
                  try {
                    imageHash = asByteSource(screenshot).hash(sha256()).toString();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                  int numOccurrence = occurrenceByHash.getOrDefault(imageHash, 0);
                  occurrenceByHash.put(imageHash, numOccurrence + 1);
                  if (occurrenceByHash.get(imageHash) > currNumOccurrence) {
                    currNumOccurrence = occurrenceByHash.get(imageHash);
                    currImagePath = screenshot;
                  }
                }
                try {
                  Files.copy(currImagePath, newGoldensDir.resolve(imageFileName));
                  if (overrideExistingGoldens) {
                    Files.copy(currImagePath, existingGoldensDir.resolve(imageFileName));
                  }
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });

    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void main(String[] args) {
    GoldenImageFinder finder = new GoldenImageFinder();
    JCommander jCommander = new JCommander(finder);
    jCommander.addConverterFactory(new ParameterFactory());
    jCommander.parse(args);
    finder.run();
  }
}
