package google.registry.flows;

import org.joda.time.DateTime;

import dagger.Module;
import dagger.Provides;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.domain.ExtraDomainValidation;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.registry.label.ReservationType;

/** Dagger module for running EPP tests. */
@Module
public class EppTestFlowInjectionModule {

  @Provides
  public ExtraDomainValidation provideExtraDomainValidation() {
    return new ExtraDomainValidation() {
      @Override
      public void validateDomainCreate(
          String label, String tld, LaunchCreateExtension launchCreate, DateTime now)
          throws EppException {
        if ("disallowed".equalsIgnoreCase(label) && "tld".equalsIgnoreCase(tld)) {
          throw new ExtraDomainError();
        }
      }

      @Override
      public String getExtraValidationBlockMessage(
          String label, String tld, ReservationType reservationType, DateTime now) {
        return ("disallowed".equalsIgnoreCase(label) && "tld".equalsIgnoreCase(tld))
            ? "disallowed"
            : null;
      }
    };
  }

  /** Error used during {@link ExtraDomainValidation} injection tests. */
  public static class ExtraDomainError extends UnimplementedOptionException {
    public ExtraDomainError() {
      super("disallowed");
    }
  }
}
