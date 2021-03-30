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

import static google.registry.tools.CommandUtilities.promptForYes;

import com.beust.jcommander.Parameter;
import com.google.common.base.Strings;

/** A {@link Command} that implements a confirmation step before executing. */
public abstract class ConfirmingCommand implements Command {

  @Parameter(
      names = {"-f", "--force"},
      description = "Do not prompt before executing")
  boolean force;

  @Override
  public final void run() throws Exception {
    if (checkExecutionState()) {
      init();
      printLineIfNotEmpty(prompt());
      if (dontRunCommand()) {
        // This typically happens when all of the work is accomplished inside of prompt(), so do
        // nothing further.
        return;
      } else if (force || promptForYes("Perform this command?")) {
        System.out.println("Running ... ");
        System.out.println(execute());
        printLineIfNotEmpty(postExecute());
      } else {
        System.out.println("Command aborted.");
      }
    }
  }

  /** Run any pre-execute command checks and return true if they all pass. */
  protected boolean checkExecutionState() {
    return true;
  }

  /** Initializes the command. */
  protected void init() throws Exception {}

  /** Whether to NOT run the command. Override to true for dry-run commands. */
  protected boolean dontRunCommand() {
    return false;
  }

  /** Returns the optional extra confirmation prompt for the command. */
  protected String prompt() throws Exception {
    return "";
  }

  /** Perform the command and return a result description. */
  protected abstract String execute() throws Exception;

  /**
   * Perform any post-execution steps (e.g. verifying the result), and return a description String
   * to be printed if non-empty.
   */
  protected String postExecute() {
    return "";
  }

  /** Prints the provided text with a trailing newline, if text is not null or empty. */
  private static void printLineIfNotEmpty(String text) {
    if (!Strings.isNullOrEmpty(text)) {
      System.out.println(text);
    }
  }
}
