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

package google.registry.persistence;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Expect;
import java.util.Collections;
import javax.persistence.AttributeConverter;
import javax.persistence.Entity;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests to verify persistence.xml is valid. */
@RunWith(JUnit4.class)
public class PersistenceXmlTest {

  @ClassRule public static final Expect expect = Expect.create();

  @Test
  public void verifyClassTags_containOnlyRequiredClasses() {
    ImmutableList<Class> managedClassed = PersistenceXmlUtility.getManagedClasses();

    ImmutableList<Class> unnecessaryClasses =
        managedClassed.stream()
            .filter(
                clazz ->
                    !clazz.isAnnotationPresent(Entity.class)
                        && !AttributeConverter.class.isAssignableFrom(clazz))
            .collect(toImmutableList());

    ImmutableSet<Class> duplicateClasses =
        managedClassed.stream()
            .filter(clazz -> Collections.frequency(managedClassed, clazz) > 1)
            .collect(toImmutableSet());

    expect
        .withMessage("Found duplicate <class> tags defined in persistence.xml.")
        .that(duplicateClasses)
        .isEmpty();

    expect
        .withMessage(
            "Found unnecessary <class> tags defined in persistence.xml. Only entity class and"
                + " implementation of AttributeConverter are required to be added in"
                + " persistence.xml.")
        .that(unnecessaryClasses)
        .isEmpty();
  }
}
