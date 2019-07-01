package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProbingStep_Factory<O> implements Factory<ProbingStep<O>> {
  @SuppressWarnings("rawtypes")
  private static final ProbingStep_Factory INSTANCE = new ProbingStep_Factory();

  @Override
  public ProbingStep<O> get() {
    return new ProbingStep<O>();
  }

  @SuppressWarnings("unchecked")
  public static <O> ProbingStep_Factory<O> create() {
    return INSTANCE;
  }

  public static <O> ProbingStep<O> newProbingStep() {
    return new ProbingStep<O>();
  }
}
