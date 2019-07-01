package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.Tokens.Token;
import google.registry.monitoring.blackbox.Tokens.WebWhoisToken;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TokenModule_ProvideTokenFactory implements Factory<Token> {
  private final Provider<WebWhoisToken> tokenProvider;

  public TokenModule_ProvideTokenFactory(Provider<WebWhoisToken> tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public Token get() {
    return proxyProvideToken(tokenProvider.get());
  }

  public static TokenModule_ProvideTokenFactory create(Provider<WebWhoisToken> tokenProvider) {
    return new TokenModule_ProvideTokenFactory(tokenProvider);
  }

  public static Token proxyProvideToken(WebWhoisToken token) {
    return Preconditions.checkNotNull(
        TokenModule.provideToken(token),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
