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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import google.registry.monitoring.blackbox.tokens.Token;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ProbingSequenceTest {


  private ProbingStep setupMock() {
    ProbingStep mock = Mockito.mock(ProbingStep.class);
    doCallRealMethod().when(mock).nextStep(any(ProbingStep.class));
    doCallRealMethod().when(mock).nextStep();
    return mock;
  }

  private static class Wrapper<T> {

    T data;

    public Wrapper(T data) {
      this.data = data;
    }
  }

  @Test
  public void testSequenceBasicConstruction_Success() {
    ProbingStep firstStep = setupMock();
    ProbingStep secondStep = setupMock();
    ProbingStep thirdStep = setupMock();

    Token testToken = Mockito.mock(Token.class);

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(firstStep)
        .addStep(secondStep)
        .addStep(thirdStep)
        .build();

    assertThat(firstStep.nextStep()).isEqualTo(secondStep);
    assertThat(secondStep.nextStep()).isEqualTo(thirdStep);
    assertThat(thirdStep.nextStep()).isEqualTo(firstStep);

    Wrapper<Boolean> wrapper = new Wrapper<>(false);
    doAnswer(invocation -> wrapper.data = true).when(firstStep).accept(any(Token.class));

    sequence.start();

    assertThat(wrapper.data).isTrue();
  }

  @Test
  public void testSequenceAdvancedConstruction_Success() {
    ProbingStep firstStep = setupMock();
    ProbingStep secondStep = setupMock();
    ProbingStep thirdStep = setupMock();

    Token testToken = Mockito.mock(Token.class);

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(thirdStep)
        .addStep(secondStep)
        .markFirstRepeated()
        .addStep(firstStep)
        .build();

    assertThat(firstStep.nextStep()).isEqualTo(secondStep);
    assertThat(secondStep.nextStep()).isEqualTo(firstStep);
    assertThat(thirdStep.nextStep()).isEqualTo(secondStep);

    Wrapper<Boolean> wrapper = new Wrapper<>(false);
    doAnswer(invocation -> wrapper.data = true).when(thirdStep).accept(any(Token.class));

    sequence.start();

    assertThat(wrapper.data).isTrue();
  }

}
