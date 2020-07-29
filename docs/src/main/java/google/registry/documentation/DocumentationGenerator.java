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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.RootDoc;
import google.registry.documentation.FlowDocumentation.ErrorCase;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Main entry point class for documentation generation.  An instance of this class reads data
 * via the javadoc system upon creation and stores it for answering future queries for
 * documentation information.
 */
public final class DocumentationGenerator {

  private final RootDoc sourceRoot;

  /** Returns a new DocumentationGenerator object with parsed information from javadoc. */
  public DocumentationGenerator() throws IOException {
    sourceRoot = JavadocWrapper.getRootDoc();
  }

  /** Returns generated Markdown output for the flows.  Convenience method for clients. */
  public String generateMarkdown() {
    return MarkdownDocumentationFormatter.generateMarkdownOutput(getFlowDocs());
  }

  /** Returns a list of flow documentation objects derived from this generator's data. */
  public ImmutableList<FlowDocumentation> getFlowDocs() {
    // Relevant flows are leaf flows: precisely the concrete subclasses of Flow.
    return getConcreteSubclassesStream(FlowDocumentation.BASE_FLOW_CLASS_NAME)
        .sorted(comparing(ClassDoc::typeName))
        .map(FlowDocumentation::new)
        .collect(toImmutableList());
  }

  /** Returns a list of all possible error cases that might occur. */
  public ImmutableList<ErrorCase> getAllErrors() {
    // Relevant error cases are precisely the concrete subclasses of EppException.
    return getConcreteSubclassesStream(FlowDocumentation.EXCEPTION_CLASS_NAME)
        .map(ErrorCase::new)
        .collect(toImmutableList());
  }

  /** Helper to return all concrete subclasses of a given named class. */
  private Stream<ClassDoc> getConcreteSubclassesStream(String baseClassName) {
    final ClassDoc baseFlowClassDoc = sourceRoot.classNamed(baseClassName);
    return Arrays.stream(sourceRoot.classes())
        .filter(classDoc -> classDoc.subclassOf(baseFlowClassDoc) && !classDoc.isAbstract());
  }
}
