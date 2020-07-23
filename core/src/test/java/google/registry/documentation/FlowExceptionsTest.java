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

package google.registry.documentation;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.documentation.FlowDocumentation.ErrorCase;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Test to ensure accurate documentation of flow exceptions.
 *
 * <p>This test goes through each flow and ensures that the exceptions listed in custom javadoc
 * tags on the flow class match the import statements in the test case for that flow.  Thus it
 * catches the case where someone adds a test case for an exception without updating the javadoc,
 * and the case where someone adds a javadoc tag to a flow without writing a test for this error
 * condition.  For example, there should always be a matching pair of lines such as the following:
 *
 * <pre>
 *   src/main/java/.../flows/session/LoginFlow.java:
 *     @error {&#64;link AlreadyLoggedInException}
 *
 *   src/test/java/.../flows/session/LoginFlowTest.java:
 *     import .....flows.session.LoginFlow.AlreadyLoggedInException;
 * </pre>
 *
 * If the first line is missing, this test fails and suggests adding the javadoc tag or removing
 * the import.  If the second line is missing, this test fails and suggests adding the import or
 * removing the javadoc tag.
 */
@SuppressWarnings("javadoc")
class FlowExceptionsTest {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Test
  void testExceptionCorrespondence() throws IOException {
    DocumentationGenerator docGenerator = new DocumentationGenerator();
    Set<ErrorCase> possibleErrors = Sets.newHashSet(docGenerator.getAllErrors());
    Set<String> mismatchingFlows = Sets.newHashSet();
    for (FlowDocumentation flow : docGenerator.getFlowDocs()) {
      FlowContext context = new FlowContext(flow, possibleErrors);
      String mismatches = context.getMismatchedExceptions();
      if (!mismatches.isEmpty()) {
        logger.atWarning().log("%-40s FAIL\n\n%s", flow.getName(), mismatches);
        mismatchingFlows.add(flow.getName());
      } else {
        logger.atInfo().log("%-40s OK", flow.getName());
      }
    }
    assertWithMessage(
            "Mismatched exceptions between flow documentation and tests. See test log for full "
                + "details. The set of failing flows follows.")
        .that(mismatchingFlows)
        .isEmpty();
  }
}
