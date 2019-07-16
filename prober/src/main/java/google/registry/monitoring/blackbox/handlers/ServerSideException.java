package google.registry.monitoring.blackbox.handlers;

/**
 * Base exception class for all instances when the Status of the task performed is ERROR
 */
public class ServerSideException extends Exception {

  public ServerSideException(String msg) {
    super(msg);
  }

  public ServerSideException(Throwable e) {
    super(e);
  }
}