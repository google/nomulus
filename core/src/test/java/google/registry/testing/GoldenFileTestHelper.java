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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.TestDataHelper.filePath;
import static google.registry.testing.TestDataHelper.loadFile;

import com.google.common.base.Joiner;
import com.google.common.flogger.FluentLogger;
import google.registry.request.RouterDisplayHelper;

/**
 * Helper class to compare a string against a golden file and print out update instructions if
 * necessary.
 */
public class GoldenFileTestHelper {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  String actualValue = null;
  String nomulusCommand = null;
  String goldenFileDescription = null;

  private static final String UPDATE_COMMAND =
      "../gradlew nomulus && java -jar build/libs/nomulus.jar -e localhost %s > %s";

  private static final String UPDATE_INSTRUCTIONS =
      Joiner.on('\n')
          .join(
              "",
              "-------------------------------------------------------------------------------",
              "Your changes affect the %s. To update the checked-in version, run the following"
                  + " command in the core project:",
              UPDATE_COMMAND,
              "");

  public static GoldenFileTestHelper assertThat(String actualValue) {
    return new GoldenFileTestHelper().setActualValue(actualValue);
  }

  public static GoldenFileTestHelper assertThatRoutesFromComponent(Class<?> component) {
    return assertThat(RouterDisplayHelper.extractHumanReadableRoutesFromComponent(component))
        .createdByNomulusCommand("get_routing_map -c " + component.getName());
  }

  public GoldenFileTestHelper createdByNomulusCommand(String nomulusCommand) {
    checkState(this.nomulusCommand == null, "Trying to set nomulus command twice");
    this.nomulusCommand = checkNotNull(nomulusCommand);
    return this;
  }

  public GoldenFileTestHelper describedAs(String goldenFileDescription) {
    checkState(this.goldenFileDescription == null, "Trying to set description twice");
    this.goldenFileDescription = checkNotNull(goldenFileDescription);
    return this;
  }

  public void isEqualToGolden(Class<?> context, String filename) {
    checkNotNull(nomulusCommand, "Didn't set nomulus command");
    checkNotNull(goldenFileDescription, "Didn't set description");
    checkNotNull(context);
    checkNotNull(filename);
    String expectedValue = loadFile(context, filename).trim();
    if (!actualValue.equals(expectedValue)) {
      logger.atWarning().log(
          "Actual routing map was:\n%s\n\nExpected routing map was:\n%s\n",
          actualValue, expectedValue);
      assertWithMessage(
              UPDATE_INSTRUCTIONS,
              goldenFileDescription,
              nomulusCommand,
              filePath(context, filename))
          .fail();
    }
  }

  private GoldenFileTestHelper setActualValue(String actualValue) {
    this.actualValue = checkNotNull(actualValue);
    return this;
  }
}
