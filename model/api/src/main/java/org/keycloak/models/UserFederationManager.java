package org.keycloak.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class UserFederationManager implements UserProvider {
    protected KeycloakSession session;

    public UserFederationManager(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public UserModel addUser(RealmModel realm, String id, String username, boolean addDefaultRoles) {
        UserModel user = session.userStorage().addUser(realm, id, username, addDefaultRoles);
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = session.getProvider(UserFederationProvider.class, federation.getProviderName());
            if (fed.isRegistrationSupported()) {
                return fed.register(realm, user);
            }
        }
        return user;
    }

    protected UserFederationProvider getFederationProvider(UserFederationProviderModel model) {
        UserFederationProviderFactory factory = (UserFederationProviderFactory)session.getKeycloakSessionFactory().getProviderFactory(UserFederationProvider.class, model.getProviderName());
        return factory.getInstance(session, model);

    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        UserModel user = session.userStorage().addUser(realm, username);
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            if (fed.isRegistrationSupported()) {
                return fed.register(realm, user);
            }
        }
        return user;
    }

    protected UserFederationProvider getFederationLink(RealmModel realm, UserModel user) {
        if (user.getFederationLink() == null) return null;
        for (UserFederationProviderModel fed : realm.getUserFederationProviders()) {
            if (fed.getId().equals(user.getFederationLink())) {
                return getFederationProvider(fed);
            }
        }
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null) {
            return link.removeUser(realm, user);
        }
        return session.userStorage().removeUser(realm, user);

    }

    protected void validateUser(RealmModel realm, UserModel user) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null  && !link.isValid(user)) {
            deleteInvalidUser(realm, user);
            throw new IllegalStateException("Federated user no longer valid");
        }

    }

    protected void deleteInvalidUser(RealmModel realm, UserModel user) {
        KeycloakSession tx = session.getKeycloakSessionFactory().create();
        try {
            tx.getTransaction().begin();
            RealmModel realmModel = tx.realms().getRealm(realm.getId());
            UserModel deletedUser = tx.userStorage().getUserById(user.getId(), realmModel);
            tx.userStorage().removeUser(realmModel, deletedUser);
            tx.getTransaction().commit();
        } finally {
            tx.close();
        }
    }

    protected boolean isValid(RealmModel realm, UserModel user) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null) return link.isValid(user);
        return true;
    }


    protected UserModel validateAndProxyUser(RealmModel realm, UserModel user) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null) {
            if (isValid(realm, user)) {
                return link.proxy(user);
            } else {
                deleteInvalidUser(realm, user);
                return null;
            }
        }
        return user;
    }

    @Override
    public void addSocialLink(RealmModel realm, UserModel user, SocialLinkModel socialLink) {
        validateUser(realm, user);
        session.userStorage().addSocialLink(realm, user, socialLink);
    }

    @Override
    public boolean removeSocialLink(RealmModel realm, UserModel user, String socialProvider) {
        validateUser(realm, user);
        if (user == null) throw new IllegalStateException("Federated user no longer valid");
        return session.userStorage().removeSocialLink(realm, user, socialProvider);
    }

    @Override
    public UserModel getUserById(String id, RealmModel realm) {
        UserModel user = session.userStorage().getUserById(id, realm);
        if (user != null) {
            user = validateAndProxyUser(realm, user);
        }
        return user;
    }

    @Override
    public UserModel getUserByUsername(String username, RealmModel realm) {
        UserModel user = session.userStorage().getUserByUsername(username, realm);
        if (user != null) {
            user = validateAndProxyUser(realm, user);
            if (user != null) return user;
        }
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            user = fed.getUserByUsername(realm, username);
            if (user != null) return user;
        }
        return user;
    }

    @Override
    public UserModel getUserByEmail(String email, RealmModel realm) {
        UserModel user = session.userStorage().getUserByEmail(email, realm);
        if (user != null) {
            user = validateAndProxyUser(realm, user);
            if (user != null) return user;
        }
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            user = fed.getUserByEmail(realm, email);
            if (user != null) return user;
        }
        return user;
    }

    @Override
    public UserModel getUserBySocialLink(SocialLinkModel socialLink, RealmModel realm) {
        UserModel user = session.userStorage().getUserBySocialLink(socialLink, realm);
        if (user != null) {
            user = validateAndProxyUser(realm, user);
        }
        return user;
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm) {
        return getUsers(realm, 0, Integer.MAX_VALUE);

    }

    @Override
    public int getUsersCount(RealmModel realm) {
        return session.userStorage().getUsersCount(realm);
    }

    interface PaginatedQuery {
        List<UserModel> query(RealmModel realm, int first, int max);
    }

    protected List<UserModel> query(PaginatedQuery pagedQuery, RealmModel realm, int firstResult, int maxResults) {
        List<UserModel> results = new LinkedList<UserModel>();
        if (maxResults <= 0) return results;
        int first = firstResult;
        int max = maxResults;
        do {
            List<UserModel> query = pagedQuery.query(realm, first, max);
            if (query == null || query.size() == 0) return results;
            int added = 0;
            for (UserModel user : query) {
                user = validateAndProxyUser(realm, user);
                if (user == null) continue;
                results.add(user);
                added++;
            }
            if (results.size() == maxResults) return results;
            if (query.size() < max) return results;
            first = query.size();
            max -= added;
        } while (true);
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm, int firstResult, int maxResults) {
        return query(new PaginatedQuery() {
            @Override
            public List<UserModel> query(RealmModel realm, int first, int max) {
                return session.userStorage().getUsers(realm, first, max);
            }
        }, realm, firstResult, maxResults);
    }

    @Override
    public List<UserModel> searchForUser(String search, RealmModel realm) {
        return searchForUser(search, realm, 0, Integer.MAX_VALUE);
    }

    void federationLoad(RealmModel realm, Map<String, String> attributes) {
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            fed.searchByAttributes(attributes, realm);
        }
    }

    @Override
    public List<UserModel> searchForUser(final String search, RealmModel realm, int firstResult, int maxResults) {
        Map<String, String> attributes = new HashMap<String, String>();
        int spaceIndex = search.lastIndexOf(' ');
        if (spaceIndex > -1) {
            String firstName = search.substring(0, spaceIndex).trim();
            String lastName = search.substring(spaceIndex).trim();
            attributes.put(UserModel.FIRST_NAME, firstName);
            attributes.put(UserModel.LAST_NAME, lastName);
        } else if (search.indexOf('@') > -1) {
            attributes.put(UserModel.USERNAME, search.trim());
            attributes.put(UserModel.EMAIL, search.trim());
        } else {
            attributes.put(UserModel.LAST_NAME, search.trim());
            attributes.put(UserModel.USERNAME, search.trim());
        }
        federationLoad(realm, attributes);
        return query(new PaginatedQuery() {
            @Override
            public List<UserModel> query(RealmModel realm, int first, int max) {
                return session.userStorage().searchForUser(search, realm, first, max);
            }
        }, realm, firstResult, maxResults);
    }

    @Override
    public List<UserModel> searchForUserByAttributes(Map<String, String> attributes, RealmModel realm) {
        return searchForUserByAttributes(attributes, realm, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<UserModel> searchForUserByAttributes(final Map<String, String> attributes, RealmModel realm, int firstResult, int maxResults) {
        federationLoad(realm, attributes);
        return query(new PaginatedQuery() {
            @Override
            public List<UserModel> query(RealmModel realm, int first, int max) {
                return session.userStorage().searchForUserByAttributes(attributes, realm, first, max);
            }
        }, realm, firstResult, maxResults);
    }

    @Override
    public Set<SocialLinkModel> getSocialLinks(UserModel user, RealmModel realm) {
        validateUser(realm, user);
        if (user == null) throw new IllegalStateException("Federated user no longer valid");
        return session.userStorage().getSocialLinks(user, realm);
    }

    @Override
    public SocialLinkModel getSocialLink(UserModel user, String socialProvider, RealmModel realm) {
        validateUser(realm, user);
        if (user == null) throw new IllegalStateException("Federated user no longer valid");
        return session.userStorage().getSocialLink(user, socialProvider, realm);
    }

    @Override
    public void preRemove(RealmModel realm) {
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            fed.preRemove(realm);
        }
        session.userStorage().preRemove(realm);
    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {
        for (UserFederationProviderModel federation : realm.getUserFederationProviders()) {
            UserFederationProvider fed = getFederationProvider(federation);
            fed.preRemove(realm, role);
        }
        session.userStorage().preRemove(realm, role);
    }

    @Override
    public boolean validCredentials(RealmModel realm, UserModel user, List<UserCredentialModel> input) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null) {
            validateUser(realm, user);
            if (link.getSupportedCredentialTypes().size() > 0) {
                List<UserCredentialModel> fedCreds = new ArrayList<UserCredentialModel>();
                List<UserCredentialModel> localCreds = new ArrayList<UserCredentialModel>();
                for (UserCredentialModel cred : input) {
                    if (fedCreds.contains(cred.getType())) {
                        fedCreds.add(cred);
                    } else {
                        localCreds.add(cred);
                    }
                }
                if (!link.validCredentials(realm, user, fedCreds)) {
                    return false;
                }
                return session.userStorage().validCredentials(realm, user, localCreds);
            }
        }
        return session.userStorage().validCredentials(realm, user, input);
    }

    @Override
    public boolean validCredentials(RealmModel realm, UserModel user, UserCredentialModel... input) {
        UserFederationProvider link = getFederationLink(realm, user);
        if (link != null) {
            validateUser(realm, user);
            Set<String> supportedCredentialTypes = link.getSupportedCredentialTypes();
            if (supportedCredentialTypes.size() > 0) {
                List<UserCredentialModel> fedCreds = new ArrayList<UserCredentialModel>();
                List<UserCredentialModel> localCreds = new ArrayList<UserCredentialModel>();
                for (UserCredentialModel cred : input) {
                    if (supportedCredentialTypes.contains(cred.getType())) {
                        fedCreds.add(cred);
                    } else {
                        localCreds.add(cred);
                    }
                }
                if (!link.validCredentials(realm, user, fedCreds)) {
                    return false;
                }
                return session.userStorage().validCredentials(realm, user, localCreds);
            }
        }
        return session.userStorage().validCredentials(realm, user, input);
    }

    @Override
    public void close() {
    }
}
