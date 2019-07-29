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


  private Bootstrap dummyBootstrap = new Bootstrap();
  private Token testToken = new ProbingSequenceTestToken();

  /**
   * Custom {@link ProbingStep} subclass that acts as a mock
   * step, so we can test how well {@link ProbingSequence} builds
   * a linked list of {@link ProbingStep}s from their {@link Builder}s.
   */
  private static class TestStep extends ProbingStep {
    private Bootstrap bootstrap;
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

    @Override
    SocketAddress address() {
      return null;
    }

    /** We want to be able to set and retrieve the bootstrap, as {@link ProbingSequence} does this. */
    @Override
    Bootstrap bootstrap() {
      return bootstrap;
    }

    /**
     * Builder for {@link TestStep}, that extends {@link ProbingStep.Builder} so that these can be
     * input into the {@link ProbingSequence.Builder}.
     */
    public static class Builder extends ProbingStep.Builder {
      /** We test to see if we accurately add the right bootstrap to all {@link ProbingStep}s/ */
      private Bootstrap bootstrap;

      /** We also mark each step in order to ensure that when running, they are arranged in the right order. */
      private String marker;

      @Override
      public ProbingStep.Builder setDuration(Duration value) {
        return this;
      }

      @Override
      public ProbingStep.Builder setProtocol(Protocol value) {
        return this;
      }

      @Override
      public ProbingStep.Builder setMessageTemplate(OutboundMessageType value) {
        return null;
      }

      @Override
      public ProbingStep.Builder setAddress(SocketAddress address) {
        return null;
      }

      @Override
      public ProbingStep.Builder setBootstrap(Bootstrap value) {
        bootstrap = value;
        return this;
      }

      public ProbingStep.Builder addMarker(String value) {
        marker = value;
        return this;
      }

      @Override
      public ProbingStep build() {
        return new TestStep(bootstrap, marker);
      }
    }
    private TestStep(Bootstrap bootstrap, String marker) {
      this.bootstrap = bootstrap;
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
    ProbingStep.Builder firstStepBuilder = new TestStep.Builder().addMarker("first");
    ProbingStep.Builder secondStepBuilder = new TestStep.Builder().addMarker("second");
    ProbingStep.Builder thirdStepBuilder = new TestStep.Builder().addMarker("third");

    ProbingSequence sequence = new ProbingSequence.Builder()
        .setBootstrap(dummyBootstrap)
        .addStep(firstStepBuilder)
        .addStep(secondStepBuilder)
        .addStep(thirdStepBuilder)
        .addToken(testToken)
        .build();

    sequence.start();

    assertThat(testToken.getHost()).isEqualTo("firstsecondthirdfirst");
  }

  @Test
  public void testSequenceAdvancedConstruction_Success() {
    ProbingStep.Builder firstStepBuilder = new TestStep.Builder().addMarker("first");
    ProbingStep.Builder secondStepBuilder = new TestStep.Builder().addMarker("second");
    ProbingStep.Builder thirdStepBuilder = new TestStep.Builder().addMarker("third");

    ProbingSequence sequence = new ProbingSequence.Builder()
        .setBootstrap(dummyBootstrap)
        .addStep(thirdStepBuilder)
        .addStep(secondStepBuilder)
        .markFirstRepeated()
        .addStep(firstStepBuilder)
        .addToken(testToken)
        .build();

    sequence.start();

    assertThat(testToken.getHost()).isEqualTo("thirdsecondfirstsecond");
  }

  @Test
  public void testSequenceConstruction_Failure() {
    ProbingStep.Builder firstStepBuilder = new TestStep.Builder().addMarker("first");
    ProbingStep.Builder secondStepBuilder = new TestStep.Builder().addMarker("second");
    ProbingStep.Builder thirdStepBuilder = new TestStep.Builder().addMarker("third");
    assertThrows(AssertionError.class, () -> {
      ProbingSequence sequence = new ProbingSequence.Builder()
          .addStep(firstStepBuilder)
          .addStep(secondStepBuilder)
          .addStep(thirdStepBuilder)
          .addToken(testToken)
          .setBootstrap(dummyBootstrap)
          .build();
    });
  }
}
