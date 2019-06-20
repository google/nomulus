package google.registry.monitoring.blackbox;


public class TestToken<O> extends Token<O> {
  @Override
  public Protocol protocol(){
    return Protocol.defaultImplementation();
  }

  @Override
  public Token next() {
    return this;
  }

  @Override
  public O message() {
    return (O) this.domainName;
  }

  @Override
  public ActionHandler actionHandler() {
    return new ActionHandler<O, O>();
  }

  public static TestToken generateNext() {
    return new TestToken();
  }
}
