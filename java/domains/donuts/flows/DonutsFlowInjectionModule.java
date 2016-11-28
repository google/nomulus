package domains.donuts.flows;

import org.joda.time.DateTime;

import dagger.Module;
import dagger.Provides;
import google.registry.flows.EppException;
import google.registry.flows.domain.ExtraDomainValidation;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.registry.label.ReservationType;

@Module
public class DonutsFlowInjectionModule {
  @Provides
  public ExtraDomainValidation provideExtraDomainValidation() {
    try {
      Class<?> validation = Class.forName("domains.donuts.flows.DonutsExtraDomainValidation");
      return (ExtraDomainValidation) validation.newInstance();
    } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
      return new EmptyExtraDomainValidation();
    }
  }

  public static class EmptyExtraDomainValidation implements ExtraDomainValidation {

    @Override
    public void validateDomainCreate(
        String label, String tld, LaunchCreateExtension launchCreate, DateTime now)
        throws EppException {}

    @Override
    public String getExtraValidationBlockMessage(
        String label, String tld, ReservationType reservationType, DateTime now) {
      return null;
    }
  }
}
