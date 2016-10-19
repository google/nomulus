package google.registry.flows;

import dagger.BindsOptionalOf;
import dagger.Module;
import google.registry.flows.domain.ExtraDomainValidation;

@Module
public abstract class FlowInjectionModule {
  @BindsOptionalOf abstract ExtraDomainValidation optionalExtraDomainValidation();
}
