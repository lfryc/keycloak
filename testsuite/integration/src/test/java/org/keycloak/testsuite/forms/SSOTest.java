/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.testsuite.forms;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.audit.Details;
import org.keycloak.services.resources.AccountService;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.OAuthClient;
import org.keycloak.testsuite.pages.AccountUpdateProfilePage;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.AppPage.RequestType;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.openqa.selenium.WebDriver;

import javax.ws.rs.core.UriBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class SSOTest {

    @ClassRule
    public static KeycloakRule keycloakRule = new KeycloakRule();

    @Rule
    public WebRule webRule = new WebRule(this);

    @WebResource
    protected OAuthClient oauth;

    @WebResource
    protected WebDriver driver;

    @WebResource
    protected AppPage appPage;

    @WebResource
    protected LoginPage loginPage;

    @WebResource
    protected AccountUpdateProfilePage profilePage;

    @Rule
    public AssertEvents events = new AssertEvents(keycloakRule);

    @Test
    public void loginSuccess() {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        
        assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());
        Assert.assertNotNull(oauth.getCurrentQuery().get(OAuth2Constants.CODE));

        String sessionId = events.expectLogin().assertEvent().getSessionId();

        appPage.open();

        oauth.openLoginForm();

        assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        profilePage.open();

        Assert.assertTrue(profilePage.isCurrent());

        String sessionId2 = events.expectLogin().detail(Details.AUTH_METHOD, "sso").removeDetail(Details.USERNAME).client("test-app").assertEvent().getSessionId();

        assertEquals(sessionId, sessionId2);

        // Expire session
        keycloakRule.removeUserSession(sessionId);

        oauth.doLogin("test-user@localhost", "password");

        String sessionId4 = events.expectLogin().assertEvent().getSessionId();
        assertNotEquals(sessionId, sessionId4);

        events.clear();
    }

}
