package org.keycloak.authentication.model;

import org.keycloak.Config;
import org.keycloak.authentication.AuthProviderConstants;
import org.keycloak.authentication.AuthenticationProvider;
import org.keycloak.authentication.AuthenticationProviderFactory;
import org.keycloak.models.KeycloakSession;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ModelAuthenticationProviderFactory implements AuthenticationProviderFactory {

    @Override
    public AuthenticationProvider create(KeycloakSession session) {
        return new ModelAuthenticationProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return AuthProviderConstants.PROVIDER_NAME_MODEL;
    }

}
