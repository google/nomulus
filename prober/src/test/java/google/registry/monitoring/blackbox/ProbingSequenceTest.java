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
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Unit Tests on {@link ProbingSequence}
 *
 * <p>First tests the construction of sequences and ensures the ordering is exactly how
 * we expect it to be.</p>
 *
 * <p>Then tests the execution of each step, by ensuring the methods treatment of any kind
 * of response from the {@link ProbingStep}s or {@link ProbingAction}s is what is expected.</p>
 */
@RunWith(JUnit4.class)
public class ProbingSequenceTest {

  /**
   * Default mock {@link ProbingAction} returned when generating an action with a mockStep.
   */
  private ProbingAction mockAction;

  /**
   * Default mock {@link ProbingStep} that will usually return a {@code mockAction} on call to
   * generate action.
   */
  private ProbingStep mockStep;

  /**
   * Default mock {@link Token} that is passed into each {@link ProbingSequence} tested.
   */
  private Token testToken;

  @Before
  public void setup() {
    // To avoid a NullPointerException, we must have a protocol return persistent connection as
    // false.
    Protocol mockProtocol = Mockito.mock(Protocol.class);
    doReturn(false).when(mockProtocol).persistentConnection();

    mockAction = Mockito.mock(ProbingAction.class);

    //In order to avoid a NullPointerException, we must have the protocol returned that stores
    // persistent connection as false.
    mockStep = Mockito.mock(ProbingStep.class);
    doReturn(mockProtocol).when(mockStep).protocol();

    testToken = Mockito.mock(Token.class);
  }

  @Test
  public void testSequenceBasicConstruction_Success() {
    ProbingStep firstStep = Mockito.mock(ProbingStep.class);
    ProbingStep secondStep = Mockito.mock(ProbingStep.class);
    ProbingStep thirdStep = Mockito.mock(ProbingStep.class);

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addElement(firstStep)
        .addElement(secondStep)
        .addElement(thirdStep)
        .build();

    assertThat(sequence.next()).isEqualTo(firstStep);
    assertThat(sequence.next()).isEqualTo(secondStep);
    assertThat(sequence.next()).isEqualTo(thirdStep);
    assertThat(sequence.next()).isEqualTo(firstStep);
  }

  @Test
  public void testSequenceAdvancedConstruction_Success() {
    ProbingStep firstStep = Mockito.mock(ProbingStep.class);
    ProbingStep secondStep = Mockito.mock(ProbingStep.class);
    ProbingStep thirdStep = Mockito.mock(ProbingStep.class);

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addElement(thirdStep)
        .addElement(secondStep)
        .childBuilder()
        .markFirstRepeated()
        .addElement(firstStep)
        .build();

    assertThat(sequence.next()).isEqualTo(thirdStep);
    assertThat(sequence.next()).isEqualTo(secondStep);
    assertThat(sequence.next()).isEqualTo(firstStep);
    assertThat(sequence.next()).isEqualTo(secondStep);

  }

  @Test
  public void testRunStep_Success() throws UndeterminedStateException {
    // Create channel for the purpose of generating channel futures.
    EmbeddedChannel channel = new EmbeddedChannel();

    //Always returns a succeeded future on call to mockAction.
    doReturn(channel.newSucceededFuture()).when(mockAction).call();

    // Has mockStep always return mockAction on call to generateAction
    doReturn(mockAction).when(mockStep).generateAction(any(Token.class));

    //Dummy step that server purpose of placeholder to test ability of ProbingSequence to move on.
    ProbingStep secondStep = Mockito.mock(ProbingStep.class);

    //Build testable sequence from mocked components.
    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addElement(mockStep)
        .addElement(secondStep)
        .build();

    sequence.start();

    assertThat(sequence.get()).isEqualTo(secondStep);
  }

  @Test
  public void testRunStep_FailureRunning() throws UndeterminedStateException {
    // Create channel for the purpose of generating channel futures.
    EmbeddedChannel channel = new EmbeddedChannel();

    // Returns a failed future when calling the generated mock action.
    doReturn(channel.newFailedFuture(new FailureException(""))).when(mockAction).call();

    // Returns mock action on call to generate action for ProbingStep.
    doReturn(mockAction).when(mockStep).generateAction(any(Token.class));

    //Dummy step that server purpose of placeholder to test ability of ProbingSequence to move on.
    ProbingStep secondStep = Mockito.mock(ProbingStep.class);

    //Build testable sequence from mocked components.
    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addElement(mockStep)
        .addElement(secondStep)
        .build();

    sequence.start();

    assertThat(sequence.get()).isEqualTo(secondStep);
  }


  @Test
  public void testRunStep_FailureGenerating() throws UndeterminedStateException {
    // Create a mock first step that returns the dummy action when called to generate an action.
    doThrow(UndeterminedStateException.class).when(mockStep).generateAction(any(Token.class));

    //Dummy step that server purpose of placeholder to test ability of ProbingSequence to move on.
    ProbingStep secondStep = Mockito.mock(ProbingStep.class);

    //Build testable sequence from mocked components.
    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addElement(mockStep)
        .addElement(secondStep)
        .build();

    // When there is an error in action, generating, the next step is immediately called in the same
    // thread, so we expect a NullPointerException to be thrown in this thread.
    assertThrows(NullPointerException.class, sequence::start);

    assertThat(sequence.get()).isEqualTo(secondStep);
  }

}
