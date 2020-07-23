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

package google.registry.model.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.model.rde.RdeNamingUtils.makePartialName;
import static google.registry.model.rde.RdeNamingUtils.makeRydeFilename;
import static org.junit.Assert.assertThrows;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdeNamingUtils}. */
class RdeNamingUtilsTest {

  @Test
  void testMakeRydeFilename_rdeDeposit() {
    assertThat(makeRydeFilename("numbness", DateTime.parse("1984-12-18TZ"), FULL, 1, 0))
        .isEqualTo("numbness_1984-12-18_full_S1_R0");
  }

  @Test
  void testMakeRydeFilename_brdaDeposit() {
    assertThat(makeRydeFilename("dreary", DateTime.parse("2000-12-18TZ"), THIN, 1, 0))
        .isEqualTo("dreary_2000-12-18_thin_S1_R0");
  }

  @Test
  void testMakeRydeFilename_revisionNumber() {
    assertThat(makeRydeFilename("wretched", DateTime.parse("2000-12-18TZ"), THIN, 1, 123))
        .isEqualTo("wretched_2000-12-18_thin_S1_R123");
  }

  @Test
  void testMakeRydeFilename_timestampNotAtTheWitchingHour_throwsIae() {
    assertThrows(
        IllegalArgumentException.class,
        () -> makeRydeFilename("wretched", DateTime.parse("2000-12-18T04:20Z"), THIN, 1, 0));
  }

  @Test
  void testMakePartialName() {
    assertThat(makePartialName("unholy", DateTime.parse("2000-12-18TZ"), THIN))
        .isEqualTo("unholy_2000-12-18_thin");
  }
}
