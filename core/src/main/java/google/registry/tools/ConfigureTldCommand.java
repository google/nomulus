// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.flogger.util.Checks.checkArgument;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.util.ListNamingUtils.convertFilePathToName;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import google.registry.model.tld.Tld;
import google.registry.tools.params.PathParameter;
import google.registry.util.Idn;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/** Command to create or update a {@link Tld} using a YAML file. */
@Parameters(separators = " =", commandDescription = "Create or update TLD using YAML")
public class ConfigureTldCommand extends MutatingCommand {

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of TLD YAML file.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path inputFile;

  @Inject ObjectMapper mapper;

  // TODO(sarahbot@): Add a breakglass setting to this tool to indicate when a TLD has been modified
  // outside of source control

  // TODO(sarahbot@): Add a check for diffs between passed in file and current TLD and exit if their
  // is no diff

  // check that currency is equal to same currency used for all money values

  // test that empty string clears default tokens

  // test that empty list clears idn tables

  // check tld does not start with a number

  // check that the premium list is present

  // check that dnswriters is valid

  // check that the reserved list is valid for the tld

  // check idn tables are valid
  @Override
  protected void init() throws Exception {
    String name = convertFilePathToName(inputFile);
    Yaml yaml = new Yaml();
    Map<String, Object> data = yaml.load(Files.newBufferedReader(inputFile));
    Set<String> tldFields =
        Arrays.stream(Tld.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    tldFields.removeAll(ImmutableSet.of("breakglassMode", "$jacocoData", "CACHE"));
    Set<String> missingFields = new HashSet<>();
    for (String field : tldFields) {
      if (!field.startsWith("DEFAULT") && !data.keySet().contains(field)) {
        missingFields.add(field);
      }
    }
    checkArgument(
        missingFields.isEmpty(),
        String.format(
            "The input file is missing data for the following fields: %s",
            missingFields.toString()));
    checkArgument(
        data.get("tldStr").equals(name),
        "The input file name must match the name of the TLD it represents");
    checkArgument(
        data.get("tldUnicode").equals(Idn.toUnicode(name)),
        "The value for tldUnicode must equal the unicode representation of the TLD name");
    Tld oldTld = getTlds().contains(name) ? Tld.get(name) : null;
    Tld newTld = mapper.readValue(inputFile.toFile(), Tld.class);
    stageEntityChange(oldTld, newTld);
  }
}
