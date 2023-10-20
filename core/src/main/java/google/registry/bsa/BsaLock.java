package google.registry.bsa;

import google.registry.request.lock.LockHandler;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.Duration;

class BsaLock {

  private static final String LOCK_NAME = "all-bsa-jobs";

  private final LockHandler lockHandler;
  private final Duration leaseExpiry;

  @Inject
  BsaLock(LockHandler lockHandler, Duration leaseExpiry) {
    this.lockHandler = lockHandler;
    this.leaseExpiry = leaseExpiry;
  }

  boolean executeWithLock(Callable<Void> callable) {
    return lockHandler.executeWithLocks(callable, null, leaseExpiry, LOCK_NAME);
  }
}
