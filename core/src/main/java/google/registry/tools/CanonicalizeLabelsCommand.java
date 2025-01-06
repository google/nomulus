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

import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import google.registry.util.DomainNameUtils;
import google.registry.util.Idn;
import google.registry.util.NonFinalForTesting;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Command to clean up a set of labels and turn them into punycode. */
@Parameters(commandDescription = "Canonicalize domain labels")
final class CanonicalizeLabelsCommand implements Command {

  @Parameter(
      description = "Filename of file containing domain labels, one per line",
      required = true)
  private List<String> mainParameters;

  @NonFinalForTesting
  private static InputStream stdin = System.in;

  @Override
  public void run() throws IOException {
    Set<String> labels = new TreeSet<>();
    for (String label :
        mainParameters.isEmpty()
            ? CharStreams.readLines(new InputStreamReader(stdin, UTF_8))
            : Files.readLines(new File(mainParameters.get(0)), UTF_8)) {
      label = label.trim();
      if (label.startsWith("-")) {
        label = label.substring(1);
      }
      if (label.endsWith("-")) {
        label = label.substring(0, label.length() - 1);
      }
      String canonical = canonicalize(label);
      if (canonical.startsWith(DomainNameUtils.ACE_PREFIX)
          && Idn.toUnicode(canonical).equals(canonical)) {
        System.err.println("Bad IDN: " + label);
        continue;  // Bad IDN code points.
      }
      labels.add(canonical);
      if (!canonical.startsWith("xn--")) {
        // Using both "" and "-" to canonicalize labels.
        labels.add(canonicalize(label.replaceAll(" ", "")));
        labels.add(canonicalize(label.replaceAll(" ", "-")));
        labels.add(canonicalize(label.replaceAll("_", "")));
        labels.add(canonicalize(label.replaceAll("_", "-")));
      }
    }
    labels.remove("");  // We used "" for invalid labels.
    System.out.println(Joiner.on('\n').join(labels));
  }

  private String canonicalize(String rawLabel) {
    try {
      return canonicalizeHostname(rawLabel.replaceAll(" ", ""));
    } catch (Exception e) {
      System.err.printf("Error canonicalizing %s: %s\n", rawLabel, e.getMessage());
      return "";
    }
  }
}
