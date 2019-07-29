package google.registry.monitoring.blackbox.exceptions;

public class EppClientException extends InternalException {

  public EppClientException(String msg) {
    super(msg);
  }
  public EppClientException(Throwable e) {
    super(e);
  }
}
