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
package org.keycloak.services.resources;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.BadRequestException;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.OAuth2Constants;
import org.keycloak.account.AccountPages;
import org.keycloak.account.AccountProvider;
import org.keycloak.audit.Audit;
import org.keycloak.audit.AuditProvider;
import org.keycloak.audit.Details;
import org.keycloak.audit.Event;
import org.keycloak.audit.EventType;
import org.keycloak.authentication.AuthProviderStatus;
import org.keycloak.authentication.AuthenticationProviderException;
import org.keycloak.authentication.AuthenticationProviderManager;
import org.keycloak.models.AccountRoles;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.AuthenticationLinkModel;
import org.keycloak.models.AuthenticationProviderModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SocialLinkModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ForbiddenException;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.Auth;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.resources.flows.Flows;
import org.keycloak.services.resources.flows.OAuthRedirect;
import org.keycloak.services.resources.flows.Urls;
import org.keycloak.services.util.ResolveRelative;
import org.keycloak.services.validation.Validation;
import org.keycloak.social.SocialLoader;
import org.keycloak.social.SocialProvider;
import org.keycloak.social.SocialProviderException;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Variant;
import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class AccountService {

    private static final Logger logger = Logger.getLogger(AccountService.class);

    private static final EventType[] AUDIT_EVENTS = {EventType.LOGIN, EventType.LOGOUT, EventType.REGISTER, EventType.REMOVE_SOCIAL_LINK, EventType.REMOVE_TOTP, EventType.SEND_RESET_PASSWORD,
            EventType.SEND_VERIFY_EMAIL, EventType.SOCIAL_LINK, EventType.UPDATE_EMAIL, EventType.UPDATE_PASSWORD, EventType.UPDATE_PROFILE, EventType.UPDATE_TOTP, EventType.VERIFY_EMAIL};

    private static final Set<String> AUDIT_DETAILS = new HashSet<String>();
    static {
        AUDIT_DETAILS.add(Details.UPDATED_EMAIL);
        AUDIT_DETAILS.add(Details.EMAIL);
        AUDIT_DETAILS.add(Details.PREVIOUS_EMAIL);
        AUDIT_DETAILS.add(Details.USERNAME);
        AUDIT_DETAILS.add(Details.REMEMBER_ME);
        AUDIT_DETAILS.add(Details.REGISTER_METHOD);
        AUDIT_DETAILS.add(Details.AUTH_METHOD);
    }

    private RealmModel realm;

    @Context
    private HttpRequest request;

    @Context
    protected HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Context
    private KeycloakSession session;

    private final AppAuthManager authManager;
    private final ApplicationModel application;
    private Audit audit;
    private AccountProvider account;
    private Auth auth;
    private AuditProvider auditProvider;

    public AccountService(RealmModel realm, ApplicationModel application, Audit audit) {
        this.realm = realm;
        this.application = application;
        this.audit = audit;
        this.authManager = new AppAuthManager();
    }

    public void init() {
        auditProvider = session.getProvider(AuditProvider.class);

        account = session.getProvider(AccountProvider.class).setRealm(realm).setUriInfo(uriInfo);

        boolean passwordUpdateSupported = false;
        AuthenticationManager.AuthResult authResult = authManager.authenticateIdentityCookie(session, realm, uriInfo, headers);
        if (authResult != null) {
            auth = new Auth(realm, authResult.getToken(), authResult.getUser(), application, true);
        } else {
            authResult = authManager.authenticateBearerToken(session, realm, uriInfo, headers);
            if (authResult != null) {
                auth = new Auth(realm, authResult.getToken(), authResult.getUser(), application, false);
            }
        }
        if (authResult != null) {
            UserSessionModel userSession = authResult.getSession();
            if (userSession != null) {
                boolean associated = false;
                for (ClientSessionModel c : userSession.getClientSessions()) {
                    if (c.getClient().equals(application)) {
                        associated = true;
                        break;
                    }
                }
                if (!associated) {
                    session.sessions().createClientSession(realm, application, userSession, null, null, null);
                }
            }

            account.setUser(auth.getUser());

            AuthenticationLinkModel authLinkModel = auth.getUser().getAuthenticationLink();
            if (authLinkModel != null) {
                AuthenticationProviderModel authProviderModel = AuthenticationProviderManager.getConfiguredProviderModel(realm, authLinkModel.getAuthProvider());
                passwordUpdateSupported = authProviderModel.isPasswordUpdateSupported();
            }
        }

        boolean auditEnabled = auditProvider != null && realm.isAuditEnabled();

        account.setFeatures(realm.isSocial(), auditEnabled, passwordUpdateSupported);
    }

    public static UriBuilder accountServiceBaseUrl(UriInfo uriInfo) {
        UriBuilder base = uriInfo.getBaseUriBuilder().path(RealmsResource.class).path(RealmsResource.class, "getAccountService");
        return base;
    }

    public static UriBuilder accountServiceBaseUrl(UriBuilder base) {
        return base.path(RealmsResource.class).path(RealmsResource.class, "getAccountService");
    }

    private Response forwardToPage(String path, AccountPages page) {
        if (auth != null) {
            try {
                require(AccountRoles.MANAGE_ACCOUNT);
            } catch (ForbiddenException e) {
                return Flows.forms(session, realm, uriInfo).setError("No access").createErrorPage();
            }

            String[] referrer = getReferrer();
            if (referrer != null) {
                account.setReferrer(referrer);
            }

            return account.createResponse(page);
        } else {
            return login(path);
        }
    }

    /**
     * CORS preflight
     *
     * @return
     */
    @Path("/")
    @OPTIONS
    public Response accountPreflight() {
        return Cors.add(request, Response.ok()).auth().preflight().build();
    }

    /**
     * Get account information.
     *
     * @return
     */
    @Path("/")
    @GET
    public Response accountPage() {
        List<MediaType> types = headers.getAcceptableMediaTypes();
        if (types.contains(MediaType.WILDCARD_TYPE) || (types.contains(MediaType.TEXT_HTML_TYPE))) {
            return forwardToPage(null, AccountPages.ACCOUNT);
        } else if (types.contains(MediaType.APPLICATION_JSON_TYPE)) {
            requireOneOf(AccountRoles.MANAGE_ACCOUNT, AccountRoles.VIEW_PROFILE);

            UserRepresentation rep = ModelToRepresentation.toRepresentation(auth.getUser());
            if (rep.getAttributes() != null) {
                Iterator<String> itr = rep.getAttributes().keySet().iterator();
                while (itr.hasNext()) {
                    if (itr.next().startsWith("keycloak.")) {
                        itr.remove();
                    }
                }
            }

            return Cors.add(request, Response.ok(rep)).auth().allowedOrigins(auth.getToken()).build();
        } else {
            return Response.notAcceptable(Variant.VariantListBuilder.newInstance().mediaTypes(MediaType.TEXT_HTML_TYPE, MediaType.APPLICATION_JSON_TYPE).build()).build();
        }
    }

    public static UriBuilder totpUrl(UriBuilder base) {
        return RealmsResource.accountUrl(base).path(AccountService.class, "totpPage");
    }
    @Path("totp")
    @GET
    public Response totpPage() {
        return forwardToPage("totp", AccountPages.TOTP);
    }

    public static UriBuilder passwordUrl(UriBuilder base) {
        return RealmsResource.accountUrl(base).path(AccountService.class, "passwordPage");
    }
    @Path("password")
    @GET
    public Response passwordPage() {
        return forwardToPage("password", AccountPages.PASSWORD);
    }


    public static UriBuilder socialUrl(UriBuilder base) {
        return RealmsResource.accountUrl(base).path(AccountService.class, "socialPage");
    }

    @Path("social")
    @GET
    public Response socialPage() {
        return forwardToPage("social", AccountPages.SOCIAL);
    }

    @Path("log")
    @GET
    public Response logPage() {
        if (auth != null) {
            List<Event> events = auditProvider.createQuery().event(AUDIT_EVENTS).user(auth.getUser().getId()).maxResults(30).getResultList();
            for (Event e : events) {
                if (e.getDetails() != null) {
                    Iterator<Map.Entry<String, String>> itr = e.getDetails().entrySet().iterator();
                    while (itr.hasNext()) {
                        if (!AUDIT_DETAILS.contains(itr.next().getKey())) {
                            itr.remove();
                        }
                    }
                }
            }
            account.setEvents(events);
        }
        return forwardToPage("log", AccountPages.LOG);
    }

    @Path("sessions")
    @GET
    public Response sessionsPage() {
        if (auth != null) {
            account.setSessions(session.sessions().getUserSessions(realm, auth.getUser()));
        }
        return forwardToPage("sessions", AccountPages.SESSIONS);
    }

    /**
     * Update account information.
     *
     * Form params:
     *
     * firstName
     * lastName
     * email
     *
     * @param formData
     * @return
     */
    @Path("/")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response processAccountUpdate(final MultivaluedMap<String, String> formData) {
        if (auth == null) {
            return login(null);
        }

        require(AccountRoles.MANAGE_ACCOUNT);

        UserModel user = auth.getUser();

        String error = Validation.validateUpdateProfileForm(formData);
        if (error != null) {
            return account.setError(error).createResponse(AccountPages.ACCOUNT);
        }

        user.setFirstName(formData.getFirst("firstName"));
        user.setLastName(formData.getFirst("lastName"));

        String email = formData.getFirst("email");
        String oldEmail = user.getEmail();
        boolean emailChanged = oldEmail != null ? !oldEmail.equals(email) : email != null;

        user.setEmail(formData.getFirst("email"));

        audit.event(EventType.UPDATE_PROFILE).client(auth.getClient()).user(auth.getUser()).success();

        if (emailChanged) {
            user.setEmailVerified(false);
            audit.clone().event(EventType.UPDATE_EMAIL).detail(Details.PREVIOUS_EMAIL, oldEmail).detail(Details.UPDATED_EMAIL, email).success();
        }

        return account.setSuccess("accountUpdated").createResponse(AccountPages.ACCOUNT);
    }

    @Path("totp-remove")
    @GET
    public Response processTotpRemove() {
        if (auth == null) {
            return login("totp");
        }

        require(AccountRoles.MANAGE_ACCOUNT);

        UserModel user = auth.getUser();
        user.setTotp(false);

        audit.event(EventType.REMOVE_TOTP).client(auth.getClient()).user(auth.getUser()).success();

        return account.setSuccess("successTotpRemoved").createResponse(AccountPages.TOTP);
    }


    @Path("sessions-logout")
    @GET
    public Response processSessionsLogout() {
        if (auth == null) {
            return login("sessions");
        }

        require(AccountRoles.MANAGE_ACCOUNT);

        UserModel user = auth.getUser();
        session.sessions().removeUserSessions(realm, user);

        return Response.seeOther(Urls.accountSessionsPage(uriInfo.getBaseUri(), realm.getName())).build();
    }

    /**
     * Update the TOTP for this account.
     *
     * form parameters:
     *
     * totp - otp generated by authenticator
     * totpSecret - totp secret to register
     *
     * @param formData
     * @return
     */
    @Path("totp")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response processTotpUpdate(final MultivaluedMap<String, String> formData) {
        if (auth == null) {
            return login("totp");
        }

        require(AccountRoles.MANAGE_ACCOUNT);

        UserModel user = auth.getUser();

        String totp = formData.getFirst("totp");
        String totpSecret = formData.getFirst("totpSecret");

        if (Validation.isEmpty(totp)) {
            return account.setError(Messages.MISSING_TOTP).createResponse(AccountPages.TOTP);
        } else if (!new TimeBasedOTP().validate(totp, totpSecret.getBytes())) {
            return account.setError(Messages.INVALID_TOTP).createResponse(AccountPages.TOTP);
        }

        UserCredentialModel credentials = new UserCredentialModel();
        credentials.setType(CredentialRepresentation.TOTP);
        credentials.setValue(totpSecret);
        user.updateCredential(credentials);

        user.setTotp(true);

        audit.event(EventType.UPDATE_TOTP).client(auth.getClient()).user(auth.getUser()).success();

        return account.setSuccess("successTotp").createResponse(AccountPages.TOTP);
    }

    /**
     * Update account password
     *
     * Form params:
     *
     * password - old password
     * password-new
     * pasword-confirm
     *
     * @param formData
     * @return
     */
    @Path("password")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response processPasswordUpdate(final MultivaluedMap<String, String> formData) {
        if (auth == null) {
            return login("password");
        }

        require(AccountRoles.MANAGE_ACCOUNT);

        UserModel user = auth.getUser();

        String password = formData.getFirst("password");
        String passwordNew = formData.getFirst("password-new");
        String passwordConfirm = formData.getFirst("password-confirm");

        if (Validation.isEmpty(passwordNew)) {
            return account.setError(Messages.MISSING_PASSWORD).createResponse(AccountPages.PASSWORD);
        } else if (!passwordNew.equals(passwordConfirm)) {
            return account.setError(Messages.INVALID_PASSWORD_CONFIRM).createResponse(AccountPages.PASSWORD);
        }

        AuthenticationProviderManager authProviderManager = AuthenticationProviderManager.getManager(realm, session);
        if (Validation.isEmpty(password)) {
            return account.setError(Messages.MISSING_PASSWORD).createResponse(AccountPages.PASSWORD);
        } else if (authProviderManager.validatePassword(user, password) != AuthProviderStatus.SUCCESS) {
            return account.setError(Messages.INVALID_PASSWORD_EXISTING).createResponse(AccountPages.PASSWORD);
        }

        try {
            boolean passwordUpdateSuccess = authProviderManager.updatePassword(user, passwordNew);
            if (!passwordUpdateSuccess) {
                return account.setError("Password update failed").createResponse(AccountPages.PASSWORD);
            }
        } catch (AuthenticationProviderException ape) {
            return account.setError(ape.getMessage()).createResponse(AccountPages.PASSWORD);
        }

        audit.event(EventType.UPDATE_PASSWORD).client(auth.getClient()).user(auth.getUser()).success();

        return account.setSuccess("accountPasswordUpdated").createResponse(AccountPages.PASSWORD);
    }

    @Path("social-update")
    @GET
    public Response processSocialUpdate(@QueryParam("action") String action,
                                        @QueryParam("provider_id") String providerId) {
        if (auth == null) {
            return login("social");
        }

        require(AccountRoles.MANAGE_ACCOUNT);
        UserModel user = auth.getUser();

        if (Validation.isEmpty(providerId)) {
            return account.setError(Messages.MISSING_SOCIAL_PROVIDER).createResponse(AccountPages.SOCIAL);
        }
        AccountSocialAction accountSocialAction = AccountSocialAction.getAction(action);
        if (accountSocialAction == null) {
            return account.setError(Messages.INVALID_SOCIAL_ACTION).createResponse(AccountPages.SOCIAL);
        }

        SocialProvider provider = SocialLoader.load(providerId);
        if (provider == null) {
            return account.setError(Messages.SOCIAL_PROVIDER_NOT_FOUND).createResponse(AccountPages.SOCIAL);
        }

        if (!user.isEnabled()) {
            return account.setError(Messages.ACCOUNT_DISABLED).createResponse(AccountPages.SOCIAL);
        }

        switch (accountSocialAction) {
            case ADD:
                String redirectUri = UriBuilder.fromUri(Urls.accountSocialPage(uriInfo.getBaseUri(), realm.getName())).build().toString();

                try {
                    return Flows.social(realm, uriInfo, provider)
                            .user(user)
                            .putClientAttribute(OAuth2Constants.CLIENT_ID, Constants.ACCOUNT_MANAGEMENT_APP)
                            .putClientAttribute(OAuth2Constants.STATE, UUID.randomUUID().toString())
                            .putClientAttribute(OAuth2Constants.REDIRECT_URI, redirectUri)
                            .redirectToSocialProvider();
                } catch (SocialProviderException spe) {
                    return account.setError(Messages.SOCIAL_REDIRECT_ERROR).createResponse(AccountPages.SOCIAL);
                }
            case REMOVE:
                SocialLinkModel link = session.users().getSocialLink(user, providerId, realm);
                if (link != null) {

                    // Removing last social provider is not possible if you don't have other possibility to authenticate
                    if (session.users().getSocialLinks(user, realm).size() > 1 || user.getAuthenticationLink() != null) {
                        session.users().removeSocialLink(realm, user, providerId);

                        logger.debug("Social provider " + providerId + " removed successfully from user " + user.getUsername());

                        audit.event(EventType.REMOVE_SOCIAL_LINK).client(auth.getClient()).user(auth.getUser())
                                .detail(Details.USERNAME, link.getSocialUserId() + "@" + link.getSocialProvider())
                                .success();

                        return account.setSuccess(Messages.SOCIAL_PROVIDER_REMOVED).createResponse(AccountPages.SOCIAL);
                    } else {
                        return account.setError(Messages.SOCIAL_REMOVING_LAST_PROVIDER).createResponse(AccountPages.SOCIAL);
                    }
                } else {
                    return account.setError(Messages.SOCIAL_LINK_NOT_ACTIVE).createResponse(AccountPages.SOCIAL);
                }
            default:
                // Shouldn't happen
                logger.warn("Action is null!");
                return null;
        }
    }

    public static UriBuilder loginRedirectUrl(UriBuilder base) {
        return RealmsResource.accountUrl(base).path(AccountService.class, "loginRedirect");
    }

    @Path("login-redirect")
    @GET
    public Response loginRedirect(@QueryParam("code") String code,
                                  @QueryParam("state") String state,
                                  @QueryParam("error") String error,
                                  @QueryParam("path") String path,
                                  @QueryParam("referrer") String referrer,
                                  @Context HttpHeaders headers) {
        try {
            if (error != null) {
                logger.debug("error from oauth");
                throw new ForbiddenException("error");
            }
            if (!realm.isEnabled()) {
                logger.debug("realm not enabled");
                throw new ForbiddenException();
            }
            if (!application.isEnabled()) {
                logger.debug("account management app not enabled");
                throw new ForbiddenException();
            }
            if (code == null) {
                logger.debug("code not specified");
                throw new BadRequestException("code not specified");
            }
            if (state == null) {
                logger.debug("state not specified");
                throw new BadRequestException("state not specified");
            }

            URI accountUri = Urls.accountBase(uriInfo.getBaseUri()).path("/").build(realm.getName());
            URI redirectUri = path != null ? accountUri.resolve(path) : accountUri;
            if (referrer != null) {
                redirectUri = redirectUri.resolve("?referrer=" + referrer);
            }

            return Response.status(302).location(redirectUri).build();
        } finally {
        }
    }

    private Response login(String path) {
        OAuthRedirect oauth = new OAuthRedirect();
        String authUrl = Urls.realmLoginPage(uriInfo.getBaseUri(), realm.getName()).toString();
        oauth.setAuthUrl(authUrl);

        oauth.setClientId(Constants.ACCOUNT_MANAGEMENT_APP);

        UriBuilder uriBuilder = Urls.accountPageBuilder(uriInfo.getBaseUri()).path(AccountService.class, "loginRedirect");

        if (path != null) {
            uriBuilder.queryParam("path", path);
        }

        String referrer = uriInfo.getQueryParameters().getFirst("referrer");
        if (referrer != null) {
            uriBuilder.queryParam("referrer", referrer);
        }

        String referrerUri = uriInfo.getQueryParameters().getFirst("referrer_uri");
        if (referrerUri != null) {
            uriBuilder.queryParam("referrer_uri", referrerUri);
        }

        URI accountUri = uriBuilder.build(realm.getName());

        oauth.setStateCookiePath(accountUri.getRawPath());
        return oauth.redirect(uriInfo, accountUri.toString());
    }

    private String[] getReferrer() {
        String referrer = uriInfo.getQueryParameters().getFirst("referrer");
        if (referrer == null) {
            return null;
        }

        String referrerUri = uriInfo.getQueryParameters().getFirst("referrer_uri");

        ApplicationModel application = realm.getApplicationByName(referrer);
        if (application != null) {
            if (referrerUri != null) {
                referrerUri = TokenService.verifyRedirectUri(uriInfo, referrerUri, realm, application);
            } else {
                referrerUri = ResolveRelative.resolveRelativeUri(uriInfo.getRequestUri(), application.getBaseUrl());
            }

            if (referrerUri != null) {
                return new String[]{referrer, referrerUri};
            }
        } else if (referrerUri != null) {
            ClientModel client = realm.getOAuthClient(referrer);
            if (client != null) {
                referrerUri = TokenService.verifyRedirectUri(uriInfo, referrerUri, realm, application);

                if (referrerUri != null) {
                    return new String[]{referrer, referrerUri};
                }
            }
        }

        return null;
    }

    public void require(String role) {
        if (auth == null) {
            throw new ForbiddenException();
        }

        if (!auth.hasAppRole(application, role)) {
            throw new ForbiddenException();
        }
    }

    public void requireOneOf(String... roles) {
        if (auth == null) {
            throw new ForbiddenException();
        }

        if (!auth.hasOneOfAppRole(application, roles)) {
            throw new ForbiddenException();
        }
    }

    public enum AccountSocialAction {
        ADD,
        REMOVE;

        public static AccountSocialAction getAction(String action) {
            if ("add".equalsIgnoreCase(action)) {
                return ADD;
            } else if ("remove".equalsIgnoreCase(action)) {
                return REMOVE;
            } else {
                return null;
            }
        }
    }

}
