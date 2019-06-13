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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.MoreCollectors.onlyElement;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.SeeTag;
import com.sun.javadoc.Tag;
import google.registry.model.eppoutput.Result.Code;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Class to represent documentation information for a single EPP flow.
 *
 * <p>The static method getFlowDocs() on this class returns a list of FlowDocumentation
 * instances corresponding to the leaf flows in the flows package, constructing the instances
 * from class information returned from the javadoc system.  Each instance has methods for
 * retrieving relevant information about the flow, such as a description, error conditions, etc.
 */
public class FlowDocumentation {

  /** Constants for names of various relevant packages and classes. */
  static final String FLOW_PACKAGE_NAME = "google.registry.flows";
  static final String BASE_FLOW_CLASS_NAME = FLOW_PACKAGE_NAME + ".Flow";
  static final String EXCEPTION_CLASS_NAME = FLOW_PACKAGE_NAME + ".EppException";
  static final String CODE_ANNOTATION_NAME = EXCEPTION_CLASS_NAME + ".EppResultCode";

  /** Name of the class for this flow. */
  private final String name;

  /** Fully qualified name of the class for this flow. */
  private final String qualifiedName;

  /** Name of the package in which this flow resides. */
  private final String packageName;

  /** Class docs for the flow. */
  private final String classDocs;

  /** Javadoc-tagged error conditions for this flow in list form. */
  private final List<ErrorCase> errors;

  /** Javadoc-tagged error conditions for this flow, organized by underlying error code. */
  private final ListMultimap<Long, ErrorCase> errorsByCode;

  /**
   * Creates a FlowDocumentation for this flow class using data from javadoc tags.  Not public
   * because clients should get FlowDocumentation objects via the DocumentationGenerator class.
   */
  protected FlowDocumentation(ClassDoc flowDoc) {
    name = flowDoc.name();
    qualifiedName = flowDoc.qualifiedName();
    packageName = flowDoc.containingPackage().name();
    classDocs = flowDoc.commentText();
    errors = new ArrayList<>();
    // Store error codes in sorted order, and leave reasons in insert order.
    errorsByCode =
        Multimaps.newListMultimap(new TreeMap<Long, Collection<ErrorCase>>(), ArrayList::new);
    parseTags(flowDoc);
  }

  public String getName() {
    return name;
  }

  public String getQualifiedName() {
    return qualifiedName;
  }

  public String getPackageName() {
    return packageName;
  }

  public String getClassDocs() {
    return classDocs;
  }

  public ImmutableList<ErrorCase> getErrors() {
    return ImmutableList.copyOf(errors);
  }

  public ImmutableMultimap<Long, ErrorCase> getErrorsByCode() {
    return ImmutableMultimap.copyOf(errorsByCode);
  }

  /** Iterates through javadoc tags on the underlying class and calls specific parsing methods. */
  private void parseTags(ClassDoc flowDoc) {
    for (Tag tag : flowDoc.tags()) {
      // Everything else is not a relevant tag.
      if ("@error".equals(tag.name())) {
        parseErrorTag(tag);
      }
    }
  }

  /** Exception to throw when an @error tag cannot be parsed correctly. */
  private static class BadErrorTagFormatException extends IllegalStateException {
    /** Makes a message to use as a prefix for the reason passed up to the superclass. */
    private static String makeMessage(String reason, Tag tag) {
      return String.format("Bad @error tag format at %s - %s", tag.position(), reason);
    }

    private BadErrorTagFormatException(String reason, Tag tag) {
      super(makeMessage(reason, tag));
    }

    private BadErrorTagFormatException(String reason, Tag tag, Exception cause) {
      super(makeMessage(reason, tag), cause);
    }
  }

  /** Parses a javadoc tag corresponding to an error case and updates the error mapping. */
  private void parseErrorTag(Tag tag) {
    // Parse the @error tag text to find the @link inline tag.
    SeeTag linkedTag;
    try {
      linkedTag =
          Stream.of(tag.inlineTags())
              .filter(SeeTag.class::isInstance)
              .map(SeeTag.class::cast)
              .collect(onlyElement());
    } catch (NoSuchElementException | IllegalArgumentException e) {
      throw new BadErrorTagFormatException(
          String.format("expected one @link tag in tag text but found %s: %s",
              (e instanceof NoSuchElementException ? "none" : "multiple"),
              tag.text()),
          tag, e);
    }
    // Check to see if the @link tag references a valid class.
    ClassDoc exceptionRef = linkedTag.referencedClass();
    if (exceptionRef == null) {
      throw new BadErrorTagFormatException(
          "could not resolve class from @link tag text: " + linkedTag.text(),
          tag);
    }
    // Try to convert the referenced class into an ErrorCase; fail if it's not an EppException.
    ErrorCase error;
    try {
      error = new ErrorCase(exceptionRef);
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw new BadErrorTagFormatException(
          "class referenced in @link is not a valid EppException: " + exceptionRef.qualifiedName(),
          tag, e);
    }
    // Success; store this as a parsed error case.
    errors.add(error);
    errorsByCode.put(error.getCode(), error);
  }

  /**
   * Represents an error case for a flow, with a reason for the error and the EPP error code.
   *
   * <p>This class is an immutable wrapper for the name of an EppException subclass that gets
   * thrown to indicate an error condition.  It overrides equals() and hashCode() so that
   * instances of this class can be used in collections in the normal fashion.
   */
  public static class ErrorCase {

    /** The non-qualified name of the exception class. */
    private final String name;

    /** The fully-qualified name of the exception class. */
    private final String className;

    /** The reason this error was thrown, normally documented on the low-level exception class. */
    private final String reason;

    /** The EPP error code value corresponding to this error condition. */
    private final long errorCode;

    /** Constructs an ErrorCase from the corresponding class for a low-level flow exception. */
    protected ErrorCase(ClassDoc exceptionDoc) {
      name = exceptionDoc.name();
      className = exceptionDoc.qualifiedName();
      // The javadoc comment on the class explains the reason for the error condition.
      reason = exceptionDoc.commentText();
      ClassDoc highLevelExceptionDoc = getHighLevelExceptionFrom(exceptionDoc);
      errorCode = extractErrorCode(highLevelExceptionDoc);
      checkArgument(!exceptionDoc.isAbstract(),
          "Cannot use an abstract subclass of EppException as an error case");
    }

    public String getName() {
      return name;
    }

    protected String getClassName() {
      return className;
    }

    public String getReason() {
      return reason;
    }

    public long getCode() {
      return errorCode;
    }

    /** Returns the direct subclass of EppException that this class is a subclass of (or is). */
    private ClassDoc getHighLevelExceptionFrom(ClassDoc exceptionDoc) {
      // While we're not yet at the root, move up the class hierarchy looking for EppException.
      while (exceptionDoc.superclass() != null) {
        if (exceptionDoc.superclass().qualifiedTypeName().equals(EXCEPTION_CLASS_NAME)) {
          return exceptionDoc;
        }
        exceptionDoc = exceptionDoc.superclass();
      }
      // Failure; we reached the root without finding a subclass of EppException.
      throw new IllegalArgumentException(
          String.format("Class referenced is not a subclass of %s", EXCEPTION_CLASS_NAME));
    }

    /** Returns the corresponding EPP error code for an annotated subclass of EppException. */
    private long extractErrorCode(ClassDoc exceptionDoc) {
      try {
        // We're looking for a specific annotation by name that should appear only once.
        AnnotationDesc errorCodeAnnotation =
            Arrays.stream(exceptionDoc.annotations())
                .filter(
                    anno -> anno.annotationType().qualifiedTypeName().equals(CODE_ANNOTATION_NAME))
                .findFirst()
                .get();
        // The annotation should have one element whose value converts to an EppResult.Code.
        AnnotationDesc.ElementValuePair pair = errorCodeAnnotation.elementValues()[0];
        String enumConstant = ((FieldDoc) pair.value().value()).name();
        return Code.valueOf(enumConstant).code;
      } catch (IllegalStateException e) {
        throw new IllegalStateException(
            "No error code annotation found on exception " + exceptionDoc.name(), e);
      } catch (ArrayIndexOutOfBoundsException | ClassCastException | IllegalArgumentException e) {
        throw new IllegalStateException("Bad annotation on exception " + exceptionDoc.name(), e);
      }
    }

    @Override
    public boolean equals(@Nullable Object object) {
      // The className field canonically identifies the EppException wrapped by this class, and
      // all other instance state is derived from that exception, so we only check className.
      return object instanceof ErrorCase && this.className.equals(((ErrorCase) object).className);
    }

    @Override
    public int hashCode() {
      // See note for equals() - only className is needed for comparisons.
      return className.hashCode();
    }
  }
}
