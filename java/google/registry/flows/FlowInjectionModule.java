package google.registry.flows;

import dagger.BindsOptionalOf;
import dagger.Module;
import google.registry.flows.domain.ExtraDomainValidation;

/** Dagger module which provides injection points into the epp flows */
@Module
public abstract class FlowInjectionModule {
  @BindsOptionalOf
  abstract ExtraDomainValidation optionalExtraDomainValidation();
}
