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

import static com.google.common.collect.Iterables.getOnlyElement;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import java.util.ArrayList;
import java.util.List;

/** A command to display per-command usage. */
@Parameters(commandDescription = "Get usage for a command")
final class HelpCommand implements Command {

  private final JCommander jcommander;

  HelpCommand(JCommander jcommander) {
    this.jcommander = jcommander;
  }

  @Parameter(description = "<command>")
  private List<String> mainParameters = new ArrayList<>();

  @Override
  public void run() {
    String target = getOnlyElement(mainParameters, null);
    if (target == null) {
      jcommander.usage();
    } else {
      jcommander.usage(target);
    }
  }
}
