

package google.registry.monitoring.blackbox;

import io.netty.bootstrap.Bootstrap;
import javax.annotation.Generated;
import org.joda.time.Duration;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_NewChannelAction<O> extends NewChannelAction<O> {

  private final Duration delay;

  private final O outboundMessage;

  private final Protocol protocol;

  private final ActionHandler actionHandler;

  private final Bootstrap bootstrap;

  private AutoValue_NewChannelAction(
      Duration delay,
      O outboundMessage,
      Protocol protocol,
      ActionHandler actionHandler,
      Bootstrap bootstrap) {
    this.delay = delay;
    this.outboundMessage = outboundMessage;
    this.protocol = protocol;
    this.actionHandler = actionHandler;
    this.bootstrap = bootstrap;
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
  Protocol protocol() {
    return protocol;
  }

  @Override
  ActionHandler actionHandler() {
    return actionHandler;
  }

  @Override
  Bootstrap bootstrap() {
    return bootstrap;
  }

  @Override
  public String toString() {
    return "NewChannelAction{"
         + "delay=" + delay + ", "
         + "outboundMessage=" + outboundMessage + ", "
         + "protocol=" + protocol + ", "
         + "actionHandler=" + actionHandler + ", "
         + "bootstrap=" + bootstrap
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof NewChannelAction) {
      NewChannelAction<?> that = (NewChannelAction<?>) o;
      return this.delay.equals(that.delay())
          && this.outboundMessage.equals(that.outboundMessage())
          && this.protocol.equals(that.protocol())
          && this.actionHandler.equals(that.actionHandler())
          && this.bootstrap.equals(that.bootstrap());
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
    h$ ^= protocol.hashCode();
    h$ *= 1000003;
    h$ ^= actionHandler.hashCode();
    h$ *= 1000003;
    h$ ^= bootstrap.hashCode();
    return h$;
  }

  static final class Builder<O> extends NewChannelAction.Builder<O> {
    private Duration delay;
    private O outboundMessage;
    private Protocol protocol;
    private ActionHandler actionHandler;
    private Bootstrap bootstrap;
    Builder() {
    }
    @Override
    public NewChannelAction.Builder<O> delay(Duration delay) {
      if (delay == null) {
        throw new NullPointerException("Null delay");
      }
      this.delay = delay;
      return this;
    }
    @Override
    public NewChannelAction.Builder<O> outboundMessage(O outboundMessage) {
      if (outboundMessage == null) {
        throw new NullPointerException("Null outboundMessage");
      }
      this.outboundMessage = outboundMessage;
      return this;
    }
    @Override
    public NewChannelAction.Builder<O> protocol(Protocol protocol) {
      if (protocol == null) {
        throw new NullPointerException("Null protocol");
      }
      this.protocol = protocol;
      return this;
    }
    @Override
    public NewChannelAction.Builder<O> actionHandler(ActionHandler actionHandler) {
      if (actionHandler == null) {
        throw new NullPointerException("Null actionHandler");
      }
      this.actionHandler = actionHandler;
      return this;
    }
    @Override
    public NewChannelAction.Builder<O> bootstrap(Bootstrap bootstrap) {
      if (bootstrap == null) {
        throw new NullPointerException("Null bootstrap");
      }
      this.bootstrap = bootstrap;
      return this;
    }
    @Override
    public NewChannelAction<O> build() {
      String missing = "";
      if (this.delay == null) {
        missing += " delay";
      }
      if (this.outboundMessage == null) {
        missing += " outboundMessage";
      }
      if (this.protocol == null) {
        missing += " protocol";
      }
      if (this.actionHandler == null) {
        missing += " actionHandler";
      }
      if (this.bootstrap == null) {
        missing += " bootstrap";
      }
      if (!missing.isEmpty()) {
        throw new IllegalStateException("Missing required properties:" + missing);
      }
      return new AutoValue_NewChannelAction<O>(
          this.delay,
          this.outboundMessage,
          this.protocol,
          this.actionHandler,
          this.bootstrap);
    }
  }

}
