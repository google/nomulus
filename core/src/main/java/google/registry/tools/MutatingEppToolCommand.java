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

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.Parameter;

/**
 * A command to execute an epp command that intends to mutate objects
 * (i.e. enables a dry run option).
 */
public abstract class MutatingEppToolCommand extends EppToolCommand {

  @Parameter(
      names = {"-d", "--dry_run"},
      description = "Do not actually commit any mutations")
  boolean dryRun;

  @Override
  protected boolean checkExecutionState() {
    checkArgument(!(force && isDryRun()), "--force and --dry_run are incompatible");
    return true;
  }

  @Override
  protected boolean isDryRun() {
    return dryRun;
  }

  @Override
  protected void initEppToolCommand() throws Exception {
    initMutatingEppToolCommand();
  }

  protected abstract void initMutatingEppToolCommand() throws Exception;
}
