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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.DocTree.Kind;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import google.registry.flows.EppException;
import google.registry.model.eppoutput.Result.Code;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import jdk.javadoc.doclet.DocletEnvironment;

/**
 * Class to represent documentation information for a single EPP flow.
 *
 * <p>The static method {@link DocumentationGenerator#getFlowDocs} returns a list of {@link
 * FlowDocumentation} instances corresponding to the leaf flows in the flows package, constructing
 * the instances from class information returned from the javadoc system. Each instance has methods
 * for retrieving relevant information about the flow, such as a description, error conditions, etc.
 */
public class FlowDocumentation {

  /** Constants for names of various relevant packages and classes. */
  static final String FLOW_PACKAGE_NAME = "google.registry.flows";

  static final String BASE_FLOW_CLASS_NAME = FLOW_PACKAGE_NAME + ".Flow";
  static final String EXCEPTION_CLASS_NAME = FLOW_PACKAGE_NAME + ".EppException";
  static final String CODE_ANNOTATION_NAME = EXCEPTION_CLASS_NAME + ".EppResultCode";

  /** Root of the source doclet environment. */
  private final DocletEnvironment sourceRoot;

  /** Type Element of the class. */
  private final TypeElement typeElement;

  /** Doc tree for the flow. */
  private final DocCommentTree docTree;

  /** Javadoc-tagged error conditions for this flow in list form. */
  private final List<ErrorCase> errors;

  /** Javadoc-tagged error conditions for this flow, organized by underlying error code. */
  private final ListMultimap<Long, ErrorCase> errorsByCode;

  /**
   * Creates a {@link FlowDocumentation} for this flow class using data from javadoc tags. Not
   * public because clients should get FlowDocumentation objects via the DocumentationGenerator
   * class.
   */
  protected FlowDocumentation(TypeElement typeElement, DocletEnvironment sourceRoot) {
    this.sourceRoot = sourceRoot;
    this.typeElement = typeElement;
    this.docTree = sourceRoot.getDocTrees().getDocCommentTree(typeElement);
    errors = new ArrayList<>();
    // Store error codes in sorted order, and leave reasons in insert order.
    errorsByCode = Multimaps.newListMultimap(new TreeMap<>(), ArrayList::new);
    parseTags();
  }

  /** Name of the class for this flow. */
  public String getName() {
    return typeElement.getSimpleName().toString();
  }

  /** Fully qualified name of the class for this flow. */
  public String getQualifiedName() {
    return typeElement.getQualifiedName().toString();
  }

  /** Name of the package in which this flow resides. */
  public String getPackageName() {
    return sourceRoot.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
  }

  /** Javadoc of the class. */
  public String getDocTree() {
    StringJoiner joiner = new StringJoiner("");
    docTree.getFullBody().forEach(docTree -> joiner.add(docTree.toString()));
    return joiner.toString();
  }

  public ImmutableList<ErrorCase> getErrors() {
    return ImmutableList.copyOf(errors);
  }

  public ImmutableMultimap<Long, ErrorCase> getErrorsByCode() {
    return ImmutableMultimap.copyOf(errorsByCode);
  }

  /** Iterates through javadoc tags on the underlying class and calls specific parsing methods. */
  private void parseTags() {
    for (DocTree tag : docTree.getBlockTags()) {
      if (tag.getKind() == DocTree.Kind.UNKNOWN_BLOCK_TAG) {
        UnknownBlockTagTree unknownBlockTagTree = (UnknownBlockTagTree) tag;
        // Everything else is not a relevant tag.
        if (unknownBlockTagTree.getTagName().equals("error")) {
          parseErrorTag(unknownBlockTagTree);
        }
      }
    }
  }

  /** Exception to throw when an @error tag cannot be parsed correctly. */
  private static class BadErrorTagFormatException extends IllegalStateException {
    /** Makes a message to use as a prefix for the reason passed up to the superclass. */
    private static String makeMessage(
        String reason, TypeElement typeElement, UnknownBlockTagTree tagTree) {
      return String.format(
          "Bad @error tag format (%s) in class %s - %s",
          tagTree.toString(), typeElement.getQualifiedName(), reason);
    }

    private BadErrorTagFormatException(
        String reason, TypeElement typeElement, UnknownBlockTagTree tagTree) {
      super(makeMessage(reason, typeElement, tagTree));
    }

    private BadErrorTagFormatException(
        String reason, TypeElement typeElement, UnknownBlockTagTree tagTree, Exception cause) {
      super(makeMessage(reason, typeElement, tagTree), cause);
    }
  }

  /** Parses a javadoc tag corresponding to an error case and updates the error mapping. */
  private void parseErrorTag(UnknownBlockTagTree tagTree) {
    // Parse the @error tag text to find the @link inline tag.
    LinkTree linkedTag;
    try {
      linkedTag =
          tagTree.getContent().stream()
              .filter(docTree -> docTree.getKind() == Kind.LINK)
              .map(LinkTree.class::cast)
              .collect(onlyElement());
    } catch (NoSuchElementException | IllegalArgumentException e) {
      throw new BadErrorTagFormatException(
          String.format(
              "expected one @link tag in tag text but found %s: %s",
              (e instanceof NoSuchElementException ? "none" : "multiple"), tagTree.toString()),
          typeElement,
          tagTree,
          e);
    }
    // Check to see if the @link tag references a valid class.
    ReferenceTree referenceTree = linkedTag.getReference();
    TypeElement referencedTypeElement = getReferencedElement(referenceTree);
    if (referencedTypeElement == null) {
      throw new BadErrorTagFormatException(
          "could not resolve class from @link tag text: " + linkedTag.toString(),
          typeElement,
          tagTree);
    }
    // Try to convert the referenced class into an ErrorCase; fail if it's not an EppException.
    ErrorCase error;
    try {
      DocCommentTree docCommentTree =
          sourceRoot.getDocTrees().getDocCommentTree(referencedTypeElement);
      error = new ErrorCase(referencedTypeElement, docCommentTree, sourceRoot.getTypeUtils());
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw new BadErrorTagFormatException(
          "class referenced in @link is not a valid EppException: "
              + referencedTypeElement.getQualifiedName(),
          typeElement,
          tagTree,
          e);
    }
    // Success; store this as a parsed error case.
    errors.add(error);
    errorsByCode.put(error.getCode(), error);
  }

  /**
   * Try to find the {@link TypeElement} of the class in the {@link ReferenceTree}.
   *
   * <p>Unfortunately the new Javadoc API doesn't expose the referenced class object directly, so we
   * have to find it by trying to find out its fully qualified class name and then loading it from
   * the {@link Elements}.
   */
  private TypeElement getReferencedElement(ReferenceTree referenceTree) {
    String signature = referenceTree.getSignature();
    Elements elements = sourceRoot.getElementUtils();
    TypeElement referencedTypeElement = elements.getTypeElement(signature);
    // If the signature is already a qualified class name, we should find it directly. Otherwise
    // only the simple class name is used in the @error tag and we try to find its package name.
    if (referencedTypeElement == null) {
      // First try if the error class is in the same package as the flow class that we are
      // processing.
      referencedTypeElement =
          elements.getTypeElement(String.format("%s.%s", getPackageName(), signature));
    }
    if (referencedTypeElement == null) {
      // Then try if the error class is a nested class of the flow class that we are processing.
      referencedTypeElement =
          elements.getTypeElement(String.format("%s.%s", getQualifiedName(), signature));
    }
    if (referencedTypeElement == null) {
      // Lastly, the error class must have been imported. We read the flow class file, and try to
      // find the import statement that ends with the simple class name.
      String currentClassFilename =
          String.format(
              "%s/%s.java",
              JavadocWrapper.SOURCE_PATH, getQualifiedName().replaceAll("\\.", "\\/"));
      String unusedClassFileContent;
      try {
        unusedClassFileContent = Files.readString(Path.of(currentClassFilename), UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      // To understand this regex: the import statement must start with a new line or a semicolon,
      // followed by any number of whitespaces, the word "import" (we don't consider static import),
      // any number of whitespaces, repeats of "\w*." (this is not exactly precise, but for all
      // well-named classes it should suffice), the signature, any number of whitespaces, and
      // finally an ending semicolon. "?:" is used to designate non-capturing groups as we are only
      // interested in capturing the fully qualified class name.
      Pattern pattern =
          Pattern.compile(String.format("(?:\\n|;)\\s*import\\s*((?:\\w*.)*%s)\\s*;", signature));
      Matcher matcher = pattern.matcher(unusedClassFileContent);
      if (matcher.find()) {
        referencedTypeElement = elements.getTypeElement(matcher.group(1));
      }
    }

    return referencedTypeElement;
  }

  /**
   * Represents an error case for a flow, with a reason for the error and the EPP error code.
   *
   * <p>This class is an immutable wrapper for the name of an {@link EppException} subclass that
   * gets thrown to indicate an error condition. It overrides {@code equals()} and {@code
   * hashCode()} so that instances of this class can be used in collections in the normal fashion.
   */
  public static class ErrorCase {

    /** The non-qualified name of the exception class. */
    private final String name;

    /** The fully-qualified name of the exception class. */
    private final String className;

    /** The reason this error was thrown, normally documented on the low-level exception class. */
    private final String reason;

    /** Utility class to convert {@link TypeMirror} to {@link TypeElement}. */
    private final Types types;

    /** The EPP error code value corresponding to this error condition. */
    private final long errorCode;

    /** Constructs an ErrorCase from the corresponding class for a low-level flow exception. */
    protected ErrorCase(TypeElement typeElement, DocCommentTree commentTree, Types types) {
      name = typeElement.getSimpleName().toString();
      className = typeElement.getQualifiedName().toString();
      // The javadoc comment on the class explains the reason for the error condition.
      reason = commentTree.getFullBody().toString();
      this.types = types;
      TypeElement highLevelExceptionTypeElement = getHighLevelExceptionFrom(typeElement);
      errorCode = extractErrorCode(highLevelExceptionTypeElement);
      checkArgument(
          !typeElement.getModifiers().contains(Modifier.ABSTRACT),
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
    private TypeElement getHighLevelExceptionFrom(TypeElement typeElement) {
      // While we're not yet at the root, move up the class hierarchy looking for EppException.
      while (typeElement.getSuperclass() != null) {
        TypeElement superClassTypeElement =
            (TypeElement) types.asElement(typeElement.getSuperclass());
        if (superClassTypeElement.getQualifiedName().toString().equals(EXCEPTION_CLASS_NAME)) {
          return typeElement;
        }
        typeElement = superClassTypeElement;
      }
      // Failure; we reached the root without finding a subclass of EppException.
      throw new IllegalArgumentException(
          String.format("Class referenced is not a subclass of %s", EXCEPTION_CLASS_NAME));
    }

    /** Returns the corresponding EPP error code for an annotated subclass of EppException. */
    private long extractErrorCode(TypeElement typeElement) {
      try {
        // We're looking for a specific annotation by name that should appear only once.
        AnnotationMirror errorCodeAnnotation =
            typeElement.getAnnotationMirrors().stream()
                .filter(anno -> anno.getAnnotationType().toString().equals(CODE_ANNOTATION_NAME))
                .findFirst()
                .get();
        // The annotation should have one element whose value converts to an EppResult.Code.
        AnnotationValue value =
            errorCodeAnnotation.getElementValues().entrySet().iterator().next().getValue();
        return Code.valueOf(value.getValue().toString()).code;
      } catch (IllegalStateException e) {
        throw new IllegalStateException(
            "No error code annotation found on exception " + typeElement.getQualifiedName(), e);
      } catch (ArrayIndexOutOfBoundsException | ClassCastException | IllegalArgumentException e) {
        throw new IllegalStateException(
            "Bad annotation on exception " + typeElement.getQualifiedName(), e);
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
