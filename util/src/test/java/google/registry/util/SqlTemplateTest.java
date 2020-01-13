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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SqlTemplate}. */
@RunWith(JUnit4.class)
public class SqlTemplateTest {

  @Test
  public void testFillSqlTemplate() {
    assertThat(
        SqlTemplate.create("%TEST%")
            .put("TEST", "hello world")
            .build())
                .isEqualTo("hello world");
    assertThat(
        SqlTemplate.create("one %TWO% three")
            .put("TWO", "2")
            .build())
                .isEqualTo("one 2 three");
    assertThat(
        SqlTemplate.create("%ONE% %TWO% %THREE%")
            .put("ONE", "1")
            .put("TWO", "2")
            .put("THREE", "3")
            .build())
                .isEqualTo("1 2 3");
  }

  @Test
  public void testFillSqlTemplate_substitutionButNoVariables() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> SqlTemplate.create("").put("ONE", "1").build());
    assertThat(thrown).hasMessageThat().contains("Not found in template: ONE");
  }

  @Test
  public void testFillSqlTemplate_substitutionButMissingVariables() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("%ONE%").put("ONE", "1").put("TWO", "2").build());
    assertThat(thrown).hasMessageThat().contains("Not found in template: TWO");
  }

  @Test
  public void testFillSqlTemplate_sameKeyTwice_failsEarly() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("%ONE%").put("ONE", "1").put("ONE", "2"));
    assertThat(thrown).hasMessageThat().contains("");
  }

  @Test
  public void testFillSqlTemplate_variablesButNotEnoughSubstitutions() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("%ONE% %TWO%").put("ONE", "1").build());
    assertThat(thrown).hasMessageThat().contains("%TWO% found in template but no substitution");
  }

  @Test
  public void testFillSqlTemplate_mismatchedVariableAndSubstitution() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("%TWO%").put("TOO", "2").build());
    assertThat(thrown).hasMessageThat().contains("%TWO% found in template but no substitution");
  }

  @Test
  public void testFillSqlTemplate_missingKeyVals_whatsThePoint() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SqlTemplate.create("%TWO%").build());
    assertThat(thrown).hasMessageThat().contains("%TWO% found in template but no substitution");
  }

  @Test
  public void testFillSqlTemplate_lowercaseKey_notAllowed() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("%test%").put("test", "hello world").build());
    assertThat(thrown).hasMessageThat().contains("Bad substitution key: test");
  }

  @Test
  public void testFillSqlTemplate_substitution_disallowsSingleQuotes() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("The words are '%LOS%' and baz").put("LOS", "foo'bar"));
    assertThat(thrown).hasMessageThat().contains("Illegal characters in foo'bar");
  }

  @Test
  public void testFillSqlTemplate_substitution_disallowsDoubleQuotes() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SqlTemplate.create("The words are '%LOS%' and baz").put("LOS", "foo\"bar"));
    assertThat(thrown).hasMessageThat().contains("Illegal characters in foo\"bar");
  }

  @Test
  public void testFillSqlTemplate_quoteMismatch_throwsError() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SqlTemplate.create("The words are \"%LOS%' and baz").put("LOS", "foobar").build());
    assertThat(thrown).hasMessageThat().contains("Quote mismatch: \"%LOS%'");
  }

  @Test
  public void testFillSqlTemplate_extendedQuote_throwsError() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SqlTemplate.create("The words are '%LOS%-lol' and baz").put("LOS", "roid").build());
    assertThat(thrown).hasMessageThat().contains("Quote mismatch: '%LOS%");
  }
}
