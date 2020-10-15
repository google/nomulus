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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;

/** Entry point of Nomulus development commands. */
public class DevTool {

  /**
   * Available commands.
   *
   * <p><b>Note:</b> If changing the command-line name of any commands below, remember to resolve
   * any invocations in scripts (e.g. PDT, ICANN reporting).
   */
  public static final ImmutableMap<String, Class<? extends Command>> COMMAND_MAP =
      ImmutableMap.of(
          "dump_golden_schema", DumpGoldenSchemaCommand.class,
          "generate_sql_er_diagram", GenerateSqlErDiagramCommand.class,
          "generate_sql_schema", GenerateSqlSchemaCommand.class);

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    try (RegistryCli cli = new RegistryCli("devtool", COMMAND_MAP)) {
      cli.run(args);
    }
  }
}
