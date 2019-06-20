

package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_Protocol extends Protocol {

  private final String name;

  private final int port;

  private final String host;

  private final ImmutableList<Provider<? extends ChannelHandler>> handlerProviders;

  private AutoValue_Protocol(
      String name,
      int port,
      String host,
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    this.name = name;
    this.port = port;
    this.host = host;
    this.handlerProviders = handlerProviders;
  }

  @Override
  String name() {
    return name;
  }

  @Override
  int port() {
    return port;
  }

  @Override
  String host() {
    return host;
  }

  @Override
  ImmutableList<Provider<? extends ChannelHandler>> handlerProviders() {
    return handlerProviders;
  }

  @Override
  public String toString() {
    return "Protocol{"
         + "name=" + name + ", "
         + "port=" + port + ", "
         + "host=" + host + ", "
         + "handlerProviders=" + handlerProviders
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof Protocol) {
      Protocol that = (Protocol) o;
      return this.name.equals(that.name())
          && this.port == that.port()
          && this.host.equals(that.host())
          && this.handlerProviders.equals(that.handlerProviders());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= name.hashCode();
    h$ *= 1000003;
    h$ ^= port;
    h$ *= 1000003;
    h$ ^= host.hashCode();
    h$ *= 1000003;
    h$ ^= handlerProviders.hashCode();
    return h$;
  }

  static final class Builder extends Protocol.Builder {
    private String name;
    private Integer port;
    private String host;
    private ImmutableList<Provider<? extends ChannelHandler>> handlerProviders;
    Builder() {
    }
    @Override
    Protocol.Builder name(String name) {
      if (name == null) {
        throw new NullPointerException("Null name");
      }
      this.name = name;
      return this;
    }
    @Override
    Protocol.Builder port(int port) {
      this.port = port;
      return this;
    }
    @Override
    Protocol.Builder host(String host) {
      if (host == null) {
        throw new NullPointerException("Null host");
      }
      this.host = host;
      return this;
    }
    @Override
    Protocol.Builder handlerProviders(ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
      if (handlerProviders == null) {
        throw new NullPointerException("Null handlerProviders");
      }
      this.handlerProviders = handlerProviders;
      return this;
    }
    @Override
    Protocol build() {
      String missing = "";
      if (this.name == null) {
        missing += " name";
      }
      if (this.port == null) {
        missing += " port";
      }
      if (this.host == null) {
        missing += " host";
      }
      if (this.handlerProviders == null) {
        missing += " handlerProviders";
      }
      if (!missing.isEmpty()) {
        throw new IllegalStateException("Missing required properties:" + missing);
      }
      return new AutoValue_Protocol(
          this.name,
          this.port,
          this.host,
          this.handlerProviders);
    }
  }

}
