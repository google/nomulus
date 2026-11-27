// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyExpiryAccessPeriodModeDeserializer;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyFeatureStatusDeserializer;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyMoneyDeserializer;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyTldStateDeserializer;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.converter.TimedTransitionBaseUserType;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Guardrail verifying that only allowlisted stateless callers invoke raw {@link
 * TimedTransitionProperty#fromValueMap(ImmutableSortedMap)} in production code, preventing stateful
 * transitions ({@link TldState}, {@link TokenStatus}) from bypassing transition-graph validation.
 */
final class TimedTransitionPropertyUsageTest {

  private static final String TIMED_TRANSITION_INTERNAL_NAME =
      TimedTransitionProperty.class.getName().replace('.', '/');

  /**
   * Verified production methods permitted to call raw {@code TimedTransitionProperty.fromValueMap}.
   * Stateful properties ({@code TldState}, {@code TokenStatus}) must use validated factory methods.
   */
  private static final ImmutableSet<String> ALLOWED_FROM_VALUE_MAP_CALLERS =
      ImmutableSet.of(
          // Core factory methods on TimedTransitionProperty itself (withInitialValue, make).
          TimedTransitionProperty.class.getName() + "#withInitialValue",
          TimedTransitionProperty.class.getName() + "#make",
          // Hibernate deserialization of already-persisted rows.
          TimedTransitionBaseUserType.class.getName() + "#toEntity",
          // Stateless YAML deserializers (ExpiryAccessPeriodMode, Money, FeatureStatus).
          TimedTransitionPropertyExpiryAccessPeriodModeDeserializer.class.getName()
              + "#deserialize",
          TimedTransitionPropertyMoneyDeserializer.class.getName() + "#deserialize",
          TimedTransitionPropertyFeatureStatusDeserializer.class.getName() + "#deserialize",
          // Stateless builder setters (FeatureStatus, ExpiryAccessPeriodMode, Money).
          FeatureFlag.Builder.class.getName() + "#setStatusMap",
          Tld.Builder.class.getName() + "#setExpiryAccessPeriodTransitions",
          Tld.Builder.class.getName() + "#setCreateBillingCostTransitions",
          Tld.Builder.class.getName() + "#setRenewBillingCostTransitions",
          Tld.Builder.class.getName() + "#setEapFeeSchedule");

  @Test
  void testOnlyAllowlistedProductionCallersInvokeFromValueMap() throws Exception {
    URI mainClasspath =
        TimedTransitionProperty.class.getProtectionDomain().getCodeSource().getLocation().toURI();

    ImmutableSet.Builder<String> scannedClasses = ImmutableSet.builder();
    ImmutableSet.Builder<String> callers = ImmutableSet.builder();

    try (ScanResult scanResult =
        new ClassGraph()
            .overrideClasspath(mainClasspath)
            .acceptPackages("google.registry")
            .enableClassInfo()
            .ignoreClassVisibility()
            .scan()) {
      for (ClassInfo classInfo : scanResult.getAllClasses()) {
        scannedClasses.add(classInfo.getName());
        callers.addAll(findFromValueMapCallers(classInfo.getResource().load()));
      }
    }

    assertWithMessage("Expected to scan production classes in core/src/main")
        .that(scannedClasses.build())
        .containsAtLeast(
            Tld.Builder.class.getName(),
            AllocationToken.Builder.class.getName(),
            TimedTransitionPropertyTldStateDeserializer.class.getName());

    assertWithMessage(
            "Only allowlisted stateless callers may invoke TimedTransitionProperty.fromValueMap;"
                + " stateful transitions (TldState, TokenStatus) must use validated factories")
        .that(callers.build())
        .containsExactlyElementsIn(ALLOWED_FROM_VALUE_MAP_CALLERS);
  }

  @Test
  void testDetectorCatchesUnallowlistedCaller() throws IOException {
    String expectedCaller = UnallowlistedCaller.class.getName() + "#bypass";
    String resourcePath = "/" + UnallowlistedCaller.class.getName().replace('.', '/') + ".class";
    try (var stream = UnallowlistedCaller.class.getResourceAsStream(resourcePath)) {
      assertWithMessage("Missing bytecode resource %s", resourcePath).that(stream).isNotNull();
      assertThat(findFromValueMapCallers(stream.readAllBytes())).containsExactly(expectedCaller);
      assertThat(ALLOWED_FROM_VALUE_MAP_CALLERS).doesNotContain(expectedCaller);
    }
  }

  private static ImmutableSet<String> findFromValueMapCallers(byte[] bytecode) {
    ClassModel classModel = ClassFile.of().parse(bytecode);
    String className = classModel.thisClass().asInternalName().replace('/', '.');
    ImmutableSet.Builder<String> callers = ImmutableSet.builder();
    for (MethodModel method : classModel.methods()) {
      if (callsFromValueMap(method)) {
        callers.add(className + "#" + method.methodName().stringValue());
      }
    }
    return callers.build();
  }

  private static boolean callsFromValueMap(MethodModel method) {
    if (method.code().isEmpty()) {
      return false;
    }
    for (CodeElement element : method.code().get()) {
      if (element instanceof InvokeInstruction invoke
          && invoke.owner().asInternalName().equals(TIMED_TRANSITION_INTERNAL_NAME)
          && invoke.name().equalsString("fromValueMap")) {
        return true;
      }
    }
    return false;
  }

  /** Self-test fixture verifying that an unallowlisted {@code fromValueMap} call is detected. */
  static final class UnallowlistedCaller {
    static TimedTransitionProperty<TldState> bypass(ImmutableSortedMap<Instant, TldState> map) {
      return TimedTransitionProperty.fromValueMap(map);
    }
  }
}
