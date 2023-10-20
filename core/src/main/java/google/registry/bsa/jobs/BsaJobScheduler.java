package google.registry.bsa.jobs;

import java.util.Optional;

public abstract class BsaJobScheduler {

  public Optional<BsaJob> getJob() {
    return Optional.empty();
  }
}
