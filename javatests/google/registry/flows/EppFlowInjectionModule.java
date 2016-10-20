package google.registry.flows;

import org.joda.time.DateTime;

import dagger.Module;
import dagger.Provides;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.domain.ExtraDomainValidation;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.registry.label.ReservationType;

@Module
public class EppFlowInjectionModule {

  private final String labelStr = "extra";
  private final String tldStr = "tld";

  @Provides
  public ExtraDomainValidation provideExtraDomainValidation() {
    return new ExtraDomainValidation() {
      @Override
      public void validateDomainCreate(String label,
                                       String tld,
                                       LaunchCreateExtension launchCreate,
                                       DateTime now) throws EppException {
        if (labelStr.equalsIgnoreCase(label) && tldStr.equalsIgnoreCase(tld)) {
          throw new ExtraDomainError();
        }
      }

      @Override
      public String getExtraValidationBlockMessage(String label,
                                                   String tld,
                                                   ReservationType reservationType,
                                                   DateTime now) {
        return (labelStr.equalsIgnoreCase(label) && tldStr.equalsIgnoreCase(tld)) ? "Extra" : null;
      }
    };
  }

  public static class ExtraDomainError extends UnimplementedOptionException {
    public ExtraDomainError() {
      super("Extra Error");
    }
  }
}
