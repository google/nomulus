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
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Processor to generate {@link AttributeConverter} for {@code VKey} type. */
@SupportedAnnotationTypes("google.registry.persistence.WithVKey")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class VKeyProcessor extends AbstractProcessor {

  private static final String CONVERTER_CLASS_NAME_TEMP = "VKeyConverter_%s";

  private static final String VKEY_TYPE_METHOD_NAME = "value";

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    annotations.forEach(
        vKeyAnnotationType ->
            ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(vKeyAnnotationType))
                .forEach(
                    annotatedTypeElement -> {
                      DeclaredType entityType = getDeclaredType(annotatedTypeElement);
                      String simpleTypeName =
                          getTypeUtils().asElement(entityType).getSimpleName().toString();
                      List<AnnotationMirror> actualAnnotations =
                          annotatedTypeElement.getAnnotationMirrors().stream()
                              .filter(
                                  annotationType ->
                                      annotationType
                                          .getAnnotationType()
                                          .asElement()
                                          .equals(vKeyAnnotationType))
                              .collect(toImmutableList());
                      checkState(
                          actualAnnotations.size() == 1,
                          "%s can have only one @WithVKey annotation",
                          simpleTypeName);
                      TypeName keyType = null;
                      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                          actualAnnotations.get(0).getElementValues().entrySet()) {
                        String keyName = entry.getKey().getSimpleName().toString();
                        if (keyName.equals(VKEY_TYPE_METHOD_NAME)) {
                          try {
                            keyType =
                                TypeName.get(Class.forName(entry.getValue().getValue().toString()));
                          } catch (ClassNotFoundException e) {
                            throw new RuntimeException(
                                String.format(
                                    "VKey key class %s is not valid",
                                    entry.getValue().getValue().toString()),
                                e);
                          }
                        }
                      }
                      try {
                        createJavaFile(
                                getPackageName(annotatedTypeElement),
                                String.format(CONVERTER_CLASS_NAME_TEMP, simpleTypeName),
                                TypeName.get(entityType),
                                keyType)
                            .writeTo(processingEnv.getFiler());
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    }));
    return false;
  }

  private static JavaFile createJavaFile(
      String packageName, String converterClassName, TypeName entityType, TypeName keyType) {
    ParameterizedTypeName attributeConverter =
        ParameterizedTypeName.get(
            ClassName.get("google.registry.persistence.converter", "VKeyConverter"),
            entityType,
            keyType);

    MethodSpec getEntityClass =
        MethodSpec.methodBuilder("getEntityClass")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PROTECTED)
            .returns(ParameterizedTypeName.get(ClassName.get(Class.class), entityType))
            .addStatement("return $T.class", entityType)
            .build();

    MethodSpec getKeyClass =
        MethodSpec.methodBuilder("getKeyClass")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PROTECTED)
            .returns(ParameterizedTypeName.get(ClassName.get(Class.class), keyType))
            .addStatement("return $T.class", keyType)
            .build();

    TypeSpec.Builder classBuilder =
        TypeSpec.classBuilder(converterClassName)
            .addAnnotation(
                AnnotationSpec.builder(ClassName.get(Converter.class))
                    .addMember("autoApply", "true")
                    .build())
            .addModifiers(Modifier.FINAL)
            .superclass(attributeConverter)
            .addMethod(getEntityClass)
            .addMethod(getKeyClass);

    TypeSpec vKeyConverter = classBuilder.build();
    return JavaFile.builder(packageName, vKeyConverter).build();
  }

  private static DeclaredType getDeclaredType(Element element) {
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
