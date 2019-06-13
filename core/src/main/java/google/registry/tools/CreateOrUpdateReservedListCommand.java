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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import google.registry.tools.params.PathParameter;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Base class for specification of command line parameters common to creating and updating reserved
 * lists.
 */
public abstract class CreateOrUpdateReservedListCommand extends MutatingCommand {

  @Nullable
  @Parameter(
      names = {"-n", "--name"},
      description = "The name of this reserved list (defaults to filename if not specified).")
  String name;

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of new reserved list.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path input;

  @Nullable
  @Parameter(
      names = "--should_publish",
      description =
          "Whether the list is published to the concatenated list on Drive (defaults to true).",
      arity = 1)
  Boolean shouldPublish;
}
