package google.registry.request;

import java.lang.annotation.Documented;
import javax.inject.Qualifier;

/**
 * Dagger qualifier for the HTTP request payload as parsed JSON wrapped in Optional. Can be used for
 * any kind of request methods - GET, POST, etc. Will provide Optional.empty() if body is not
 * present.
 *
 * @see RequestModule
 */
@Qualifier
@Documented
public @interface OptionalJsonPayload {}
