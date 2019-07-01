package google.registry.monitoring.blackbox;

import dagger.MembersInjector;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class Token_MembersInjector implements MembersInjector<Token> {
  private final Provider<ActionHandler> actionHandlerProvider;

  public Token_MembersInjector(Provider<ActionHandler> actionHandlerProvider) {
    this.actionHandlerProvider = actionHandlerProvider;
  }

  public static MembersInjector<Token> create(Provider<ActionHandler> actionHandlerProvider) {
    return new Token_MembersInjector(actionHandlerProvider);
  }

  @Override
  public void injectMembers(Token instance) {
    injectActionHandler(instance, actionHandlerProvider.get());
  }

  public static void injectActionHandler(Object instance, ActionHandler actionHandler) {
    ((Token) instance).actionHandler = actionHandler;
  }
}
