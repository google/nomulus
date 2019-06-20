

package google.registry.monitoring.blackbox;

import io.netty.channel.Channel;
import javax.annotation.Generated;
import org.joda.time.Duration;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_ProbingAction<O> extends ProbingAction<O> {

  private final Duration delay;

  private final O outboundMessage;

  private final Channel channel;

  private final Protocol protocol;

  private final ActionHandler actionHandler;

  AutoValue_ProbingAction(
      Duration delay,
      O outboundMessage,
      Channel channel,
      Protocol protocol,
      ActionHandler actionHandler) {
    if (delay == null) {
      throw new NullPointerException("Null delay");
    }
    this.delay = delay;
    if (outboundMessage == null) {
      throw new NullPointerException("Null outboundMessage");
    }
    this.outboundMessage = outboundMessage;
    if (channel == null) {
      throw new NullPointerException("Null channel");
    }
    this.channel = channel;
    if (protocol == null) {
      throw new NullPointerException("Null protocol");
    }
    this.protocol = protocol;
    if (actionHandler == null) {
      throw new NullPointerException("Null actionHandler");
    }
    this.actionHandler = actionHandler;
  }

  @Override
  Duration delay() {
    return delay;
  }

  @Override
  O outboundMessage() {
    return outboundMessage;
  }

  @Override
  Channel channel() {
    return channel;
  }

  @Override
  Protocol protocol() {
    return protocol;
  }

  @Override
  ActionHandler actionHandler() {
    return actionHandler;
  }

  @Override
  public String toString() {
    return "ProbingAction{"
         + "delay=" + delay + ", "
         + "outboundMessage=" + outboundMessage + ", "
         + "channel=" + channel + ", "
         + "protocol=" + protocol + ", "
         + "actionHandler=" + actionHandler
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof ProbingAction) {
      ProbingAction<?> that = (ProbingAction<?>) o;
      return this.delay.equals(that.delay())
          && this.outboundMessage.equals(that.outboundMessage())
          && this.channel.equals(that.channel())
          && this.protocol.equals(that.protocol())
          && this.actionHandler.equals(that.actionHandler());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= delay.hashCode();
    h$ *= 1000003;
    h$ ^= outboundMessage.hashCode();
    h$ *= 1000003;
    h$ ^= channel.hashCode();
    h$ *= 1000003;
    h$ ^= protocol.hashCode();
    h$ *= 1000003;
    h$ ^= actionHandler.hashCode();
    return h$;
  }

}
