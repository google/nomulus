// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import google.registry.monitoring.blackbox.tokens.Token;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ProbingSequenceTest {

  private ProbingStep firstStep;
  private ProbingStep secondStep;
  private ProbingStep thirdStep;

  private Token testToken;

  private ProbingStep setupMockStep() {
    ProbingStep mock = Mockito.mock(ProbingStep.class);
    doCallRealMethod().when(mock).nextStep(any(ProbingStep.class));
    doCallRealMethod().when(mock).nextStep();
    return mock;
  }

  @Before
  public void setup() {
    firstStep = setupMockStep();
    secondStep = setupMockStep();
    thirdStep = setupMockStep();

    testToken = Mockito.mock(Token.class);
  }

  @Test
  public void testSequenceBasicConstruction_Success() {

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(firstStep)
        .addStep(secondStep)
        .addStep(thirdStep)
        .build();

    assertThat(firstStep.nextStep()).isEqualTo(secondStep);
    assertThat(secondStep.nextStep()).isEqualTo(thirdStep);
    assertThat(thirdStep.nextStep()).isEqualTo(firstStep);

    sequence.start();

    verify(firstStep, times(1)).accept(testToken);
  }

  @Test
  public void testSequenceAdvancedConstruction_Success() {

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(thirdStep)
        .addStep(secondStep)
        .markFirstRepeated()
        .addStep(firstStep)
        .build();

    assertThat(firstStep.nextStep()).isEqualTo(secondStep);
    assertThat(secondStep.nextStep()).isEqualTo(firstStep);
    assertThat(thirdStep.nextStep()).isEqualTo(secondStep);

    sequence.start();

    verify(thirdStep, times(1)).accept(testToken);
  }

}
