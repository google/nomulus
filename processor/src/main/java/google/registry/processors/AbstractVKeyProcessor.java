// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.processors;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** Abstract processor to generate {@link AttributeConverter} for VKey type. */
public abstract class AbstractVKeyProcessor extends AbstractProcessor {

  private static final String CONVERTER_CLASS_NAME_TEMP = "VKeyConverter_%s";
  // The method with same name should be defined in StringVKey and LongVKey
  private static final String CLASS_NAME_SUFFIX_KEY = "classNameSuffix";

  abstract Class<?> getSqlColumnType();

  abstract String getAnnotationSimpleName();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    annotations.forEach(
        vKeyAnnotationType -> {
          ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(vKeyAnnotationType))
              .forEach(
                  annotatedTypeElement -> {
                    DeclaredType entityType = getDeclaredType(annotatedTypeElement);

                    List<AnnotationMirror> actualAnnotation =
                        annotatedTypeElement.getAnnotationMirrors().stream()
                            .filter(
                                annotationType ->
                                    annotationType
                                        .getAnnotationType()
                                        .asElement()
                                        .equals(vKeyAnnotationType))
                            .collect(toImmutableList());
                    checkState(
                        actualAnnotation.size() == 1,
                        String.format(
                            "type can have only 1 %s annotation", getAnnotationSimpleName()));
                    String converterClassNameSuffix =
                        actualAnnotation.get(0).getElementValues().entrySet().stream()
                            .filter(
                                entry ->
                                    entry
                                        .getKey()
                                        .getSimpleName()
                                        .toString()
                                        .equals(CLASS_NAME_SUFFIX_KEY))
                            .map(entry -> ((String) entry.getValue().getValue()).trim())
                            .findFirst()
                            .orElse("");
                    if (converterClassNameSuffix.isEmpty()) {
                      converterClassNameSuffix =
                          getTypeUtils().asElement(entityType).getSimpleName().toString();
                    }

                    try {
                      createJavaFile(
                              getPackageName(annotatedTypeElement),
                              String.format(CONVERTER_CLASS_NAME_TEMP, converterClassNameSuffix),
                              entityType)
                          .writeTo(processingEnv.getFiler());
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
        });
    return false;
  }

  private JavaFile createJavaFile(
      String packageName, String converterClassName, TypeMirror entityTypeMirror) {
    TypeName entityType = ClassName.get(entityTypeMirror);

    ParameterizedTypeName attributeConverter =
        ParameterizedTypeName.get(
            ClassName.get("google.registry.persistence.converter", "VKeyConverter"),
            entityType,
            ClassName.get(getSqlColumnType()));

    MethodSpec getAttributeClass =
        MethodSpec.methodBuilder("getAttributeClass")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PROTECTED)
            .returns(
                ParameterizedTypeName.get(
                    ClassName.get(Class.class), ClassName.get(entityTypeMirror)))
            .addStatement("return $T.class", entityType)
            .build();

    TypeSpec vKeyConverter =
        TypeSpec.classBuilder(converterClassName)
            .addAnnotation(
                AnnotationSpec.builder(ClassName.get(Converter.class))
                    .addMember("autoApply", "true")
                    .build())
            .addModifiers(Modifier.FINAL)
            .superclass(attributeConverter)
            .addMethod(getAttributeClass)
            .build();

    return JavaFile.builder(packageName, vKeyConverter).build();
  }

  private DeclaredType getDeclaredType(Element element) {
    checkState(element.asType().getKind() == TypeKind.DECLARED, "element is not a DeclaredType");
    return (DeclaredType) element.asType();
  }

  private String getPackageName(Element element) {
    return getElementUtils().getPackageOf(element).getQualifiedName().toString();
  }

  private Elements getElementUtils() {
    return processingEnv.getElementUtils();
  }

  private Types getTypeUtils() {
    return processingEnv.getTypeUtils();
  }
}
