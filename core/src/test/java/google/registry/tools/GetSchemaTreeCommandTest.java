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

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.model.EntityClasses;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetSchemaTreeCommand}. */
class GetSchemaTreeCommandTest extends CommandTestCase<GetSchemaTreeCommand> {

  @Test
  void testAllClassesPrintedExactlyOnce() throws Exception {
    runCommand();
    String stdout = getStdoutAsString();
    for (Class<?> clazz : EntityClasses.ALL_CLASSES) {
      String printableName = GetSchemaTreeCommand.getPrintableName(clazz);
      int count = 0;
      Matcher matcher = Pattern.compile("(^|\\s)" + printableName + "\\s").matcher(stdout);
      while (matcher.find()) {
        count++;
      }
      assertWithMessage(printableName + " occurences").that(count).isEqualTo(1);
    }
  }
}
