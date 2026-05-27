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

package google.registry.flows.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.flows.domain.DomainFlowTmchUtils.SignedMarksListEmptyException;
import google.registry.flows.domain.DomainFlowTmchUtils.SignedMarksMustBeEncodedException;
import google.registry.flows.domain.DomainFlowTmchUtils.TooManySignedMarksException;
import google.registry.model.smd.AbstractSignedMark;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DomainFlowTmchUtilsTest {

  private final DomainFlowTmchUtils tmchUtils = new DomainFlowTmchUtils(null);

  @Test
  void test_verifySignedMarks_emptyList() {
    assertThrows(
        SignedMarksListEmptyException.class,
        () -> tmchUtils.verifySignedMarks(ImmutableList.of(), "example", Instant.now()));
  }

  @Test
  void test_verifySignedMarks_tooManyMarks() {
    AbstractSignedMark mark1 = Mockito.mock(AbstractSignedMark.class);
    AbstractSignedMark mark2 = Mockito.mock(AbstractSignedMark.class);
    assertThrows(
        TooManySignedMarksException.class,
        () ->
            tmchUtils.verifySignedMarks(ImmutableList.of(mark1, mark2), "example", Instant.now()));
  }

  @Test
  void test_verifySignedMarks_notEncoded() {
    AbstractSignedMark mark1 = Mockito.mock(AbstractSignedMark.class);
    assertThrows(
        SignedMarksMustBeEncodedException.class,
        () -> tmchUtils.verifySignedMarks(ImmutableList.of(mark1), "example", Instant.now()));
  }
}
