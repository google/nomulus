package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.function.Consumer;


public class ProbingStep<O> implements Consumer<Token<O>> {

  private ProbingStep<O> nextStep;

  public void nextStep(ProbingStep<O> step) {
    this.nextStep = step;
  }

  public ProbingStep nextStep() {
    return this.nextStep;
  }



  private ProbingAction generateAction(Token<O> token) {
    // Construct a new ProbingAction and return it.
    // The action is only used for one invocation
    // and uses the UniqueToken provided in the argument, to
    // populate fields in the action. The UniqueToken contains
    // all the information needed to create an action, including
    // for example the test domain name to be created/deleted and
    // the channel to reuse (for ExistingChannelActions).

    return NewChannelAction.builder()
        .protocol(token.protocol())
        .outboundMessage(token.message())
        .actionHandler(token.actionHandler())
        .delay(token.DEFAULT_DURATION)
        .build();

  }
  private Token generateNextToken(Token<O> token) {
    // Given the input token, we should be able to deduce what
    // this the token for the following steps to use. If this
    // step generates an action to create a domain, and the next
    // one checks for its existence, the domain contained in the
    // token should not change. If this is the last step in a loop
    // on the other hand, the next token should contain a new
    // domain name for the next loop.
    return token.next();

  }

  @Override
  public void accept(Token<O> token) {
    ChannelFuture future = generateAction(token).call();
    future.addListener(f -> nextStep().accept(generateNextToken(token)));
  }


}


