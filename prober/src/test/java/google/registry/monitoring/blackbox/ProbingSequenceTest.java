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

import google.registry.monitoring.blackbox.TestUtils.ProbingSequenceTestToken;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.bootstrap.Bootstrap;
import java.net.SocketAddress;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProbingSequenceTest {
  private final static String TEST_HOST = "TEST_HOST";


  private Token testToken = new ProbingSequenceTestToken();

  /**
   * Custom {@link ProbingStep} subclass that acts as a mock
   * step, so we can test how well {@link ProbingSequence} builds
   * a linked list of {@link ProbingStep}s from their {@link Builder}s.
   */
  private static class TestStep extends ProbingStep {
    private String marker;

    /** We implement all abstract methods to simply return null, as we have no use for them here. */
    @Override
    Duration duration() {
      return null;
    }

    @Override
    Protocol protocol() {
      return null;
    }

    @Override
    OutboundMessageType messageTemplate() {
      return null;
    }

    /** We want to be able to set and retrieve the bootstrap, as {@link ProbingSequence} does this. */
    @Override
    Bootstrap bootstrap() {
      return null;
    }

    public TestStep(String marker) {
      this.marker = marker;
    }

    /**
     * On a call to accept, we modify the token to reflect what the current step is, so we can get
     * from the token a string which represents each {@link ProbingStep} {@code marker} concatenated
     * in order.
     */
    @Override
    public void accept(Token token) {
      ((ProbingSequenceTestToken) token).addToHost(marker);
      if (!isLastStep) {
        nextStep().accept(token);
      } else {
        ((TestStep)nextStep()).specialAccept(token);
      }
    }

    /** We only invoke this on what we expect to be the firstRepeatedStep marked by the sequence. */
    public void specialAccept(Token token) {
      ((ProbingSequenceTestToken) token).addToHost(marker);
      return;
    }
  }

  @Test
  public void testSequenceBasicConstruction_Success() {
    ProbingStep firstStep = new TestStep("first");
    ProbingStep secondStep = new TestStep("second");
    ProbingStep thirdStep = new TestStep("third");

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(firstStep)
        .addStep(secondStep)
        .addStep(thirdStep)
        .build();

    sequence.start();

    assertThat(testToken.host()).isEqualTo("firstsecondthirdfirst");
  }

  @Test
  public void testSequenceAdvancedConstruction_Success() {
    ProbingStep firstStep = new TestStep("first");
    ProbingStep secondStep = new TestStep("second");
    ProbingStep thirdStep = new TestStep("third");

    ProbingSequence sequence = new ProbingSequence.Builder(testToken)
        .addStep(thirdStep)
        .addStep(secondStep)
        .markFirstRepeated()
        .addStep(firstStep)
        .build();

    sequence.start();

    assertThat(testToken.host()).isEqualTo("thirdsecondfirstsecond");
  }

}
