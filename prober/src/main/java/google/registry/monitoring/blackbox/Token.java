package google.registry.monitoring.blackbox;

import org.joda.time.Duration;

abstract class Token<O> {
  public static final Duration DEFAULT_DURATION = new Duration(2000L);
  protected String domainName;

  abstract Protocol protocol();
  abstract Token next();
  abstract O message();
  abstract ActionHandler actionHandler();

  private static String newDomainName(String previousName) {
    return String.format("prober-%d", System.currentTimeMillis());
  }



}
