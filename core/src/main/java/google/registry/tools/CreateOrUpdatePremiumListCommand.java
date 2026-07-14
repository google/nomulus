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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.google.common.flogger.FluentLogger;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.tools.params.PathParameter;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;

/**
 * Base class for specification of command line parameters common to creating and updating premium
 * lists.
 */
abstract class CreateOrUpdatePremiumListCommand extends ConfirmingCommand {

  @Inject Clock clock;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected List<String> inputData;
  protected CurrencyUnit currency;

  @Parameter(
      names = {"-d", "--dry_run"},
      description = "Does not execute the entity mutation")
  boolean dryRun;

  @Parameter(
      names = {"--build_environment"},
      description =
          "DO NOT USE THIS FLAG ON THE COMMAND LINE! This flag indicates the command is being run"
              + " by the build environment tools. This flag should never be used by a human user"
              + " from the command line.")
  boolean buildEnv;

  @Nullable
  @Parameter(
      names = {"-n", "--name"},
      description =
          "The name of this premium list (defaults to filename if not specified). "
              + "This is almost always the name of the TLD this premium list will be used on.")
  String name;

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of premium list to create or update.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path inputFile;

  @Override
  public String execute() throws Exception {
    String message =
        String.format("Saved premium list %s with %d entries.", name, inputData.size());
    try {
      logger.atInfo().log("Saving premium list for TLD %s.", name);
      tm().transact(() -> PremiumListDao.save(name, currency, inputData));
      logger.atInfo().log(message);
    } catch (Throwable e) {
      message = "Unexpected error saving premium list from nomulus tool command.";
      logger.atSevere().withCause(e).log(message);
    }
    return message;
  }

  @Override
  protected boolean dontRunCommand() {
    return dryRun;
  }

  @Override
  protected boolean checkExecutionState() {
    checkArgument(
        !RegistryToolEnvironment.get().equals(RegistryToolEnvironment.PRODUCTION) || buildEnv,
        "The --build_environment flag must be used when running in production");
    return super.checkExecutionState();
  }
}
