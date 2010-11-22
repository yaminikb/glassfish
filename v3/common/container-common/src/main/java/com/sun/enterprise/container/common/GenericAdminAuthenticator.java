/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.container.common;

import com.sun.logging.LogDomains;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.SecureAdmin;
import com.sun.enterprise.security.auth.realm.file.FileRealm;
import com.sun.enterprise.security.auth.realm.file.FileRealmUser;
import com.sun.enterprise.security.auth.realm.NoSuchUserException;
import com.sun.enterprise.security.auth.login.LoginContextDriver;
import com.sun.enterprise.security.*;
import com.sun.enterprise.admin.util.AdminConstants;
import com.sun.enterprise.util.SystemPropertyConstants;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.config.serverbeans.SecurityService;
import com.sun.enterprise.config.serverbeans.AuthRealm;
import com.sun.enterprise.config.serverbeans.AdminService;
import com.sun.enterprise.security.ssl.SSLUtils;
import com.sun.enterprise.util.net.NetUtils;
import java.io.IOException;
import java.security.KeyStoreException;
import java.util.Map;
import java.util.logging.Level;
import org.glassfish.common.util.admin.AuthTokenManager;
import org.glassfish.internal.api.*;
import org.glassfish.security.common.Group;
import org.jvnet.hk2.annotations.*;
import org.jvnet.hk2.component.Inhabitant;
import org.jvnet.hk2.component.Habitat;

import javax.security.auth.login.LoginException;
import javax.security.auth.Subject;
import javax.management.remote.JMXAuthenticator;
import java.util.logging.Logger;
import java.util.Enumeration;
import java.util.Set;
import java.io.File;
import java.security.KeyStore;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import org.glassfish.api.admin.ServerEnvironment;
import org.jvnet.hk2.component.PostConstruct;

/** Implementation of {@link AdminAccessController} that delegates to LoginContextDriver.
 *  @author Kedar Mhaswade (km@dev.java.net)
 *  This is still being developed. This particular implementation both authenticates and authorizes
 *  the users directly or indirectly. <p>
 *  <ul>
 *    <li> Authentication works by either calling FileRealm.authenticate() or by calling LoginContextDriver.login </li>
 *    <li> The admin users in case of administration file realm are always in a fixed group called "asadmin". In case
 *         of LDAP, the specific group relationships are enforced. </li>
 *  </ul>
 *  Note that admin security is tested only with FileRealm and LDAPRealm.
 *  @see com.sun.enterprise.security.cli.LDAPAdminAccessConfigurator
 *  @see com.sun.enterprise.security.cli.CreateFileUser
 *  @since GlassFish v3
 */
@Service
@ContractProvided(JMXAuthenticator.class)
public class GenericAdminAuthenticator implements AdminAccessController, JMXAuthenticator, PostConstruct {
    @Inject
    Habitat habitat;
    
    @Inject
    SecuritySniffer snif;

    @Inject
    volatile SecurityService ss;

    @Inject
    volatile AdminService as;

    @Inject
    LocalPassword localPassword;

    @Inject
    ServerContext sc;

    @Inject
    Domain domain;

    @Inject
    private AuthTokenManager authTokenManager;

    // filled in just-in-time only if needed for secure admin traffic
    private SSLUtils sslUtils = null;

    private SecureAdmin secureAdmin;

    @Inject
    ServerEnvironment serverEnv;

    private static LocalStringManagerImpl lsm = new LocalStringManagerImpl(GenericAdminAuthenticator.class);
    
    private final Logger logger = LogDomains.getLogger(GenericAdminAuthenticator.class,
            LogDomains.ADMIN_LOGGER);

    private KeyStore truststore = null;

    /** maps server alias to the Principal for the cert with that alias from the truststore */
    private Map<String,Principal> serverPrincipals = new HashMap<String,Principal>();

    @Override
    public void postConstruct() {
        secureAdmin = domain.getSecureAdmin();
    }


    /** Ensures that authentication and authorization works as specified in class documentation.
     *
     * @param user String representing the user name of the user doing an admin opearation
     * @param password String representing clear-text password of the user doing an admin operation
     * @param realm String representing the name of the admin realm for given server
     * @param originHost the host from which the request was sent
     * @return AdminAcessController.Access level of access to grant
     * @throws LoginException
     */
    public AdminAccessController.Access loginAsAdmin(String user, String password,
            String realm, final String originHost) throws LoginException {
        return loginAsAdmin(user, password, realm,
                originHost, Collections.EMPTY_MAP, null);
    }

    /** Ensures that authentication and authorization works as specified in class documentation.
     *
     * @param user String representing the user name of the user doing an admin opearation
     * @param password String representing clear-text password of the user doing an admin operation
     * @param realm String representing the name of the admin realm for given server
     * @param originHost the host from which the request was sent
     * @param candidateAdminIndicator String containing the special admin indicator (null if absent)
     * @param requestPrincipal Principal, typically as reported by the secure transport delivering the admin request
     * @return AdminAcessController.Access level of access to grant
     * @throws LoginException
     */
    public AdminAccessController.Access loginAsAdmin(String user, String password, String realm,
            final String originHost, final Map<String,String> authRelatedHeaders,
            final Principal requestPrincipal) throws LoginException {
        boolean isLocal = isLocalPassword(user, password); //local password gets preference
        if (isLocal) {
            logger.fine("Accepted locally-provisioned password authentication");
            return AdminAccessController.Access.FULL;
        }
        /*
         * Try to authenticate as a trusted sender first.  A trusted sender is
         * one that either presents an SSL cert that is in our truststore or
         * presents a very-short-term authentication token in the http request
         * header.
         * 
         * One reason to check for a trusted sender first is that
         * under secure admin an incoming command could have an auth token and,
         * if there is one, we'd like to consume it and retire it.  If we 
         * checked for file realm auth. first then a command submitted from a
         * shell process on a system using asadmin - which will have a limited-use
         * token - might also be using a stored password which would authenticate,
         * thereby bypassing the auth token
         * processing and allowing the token to remain valid longer than it should.
         */
        boolean result = authenticateAsTrustedSender(authRelatedHeaders, requestPrincipal);
        if (result) {
            logger.log(Level.FINE, "Authenticated as trusted sender");
            return AdminAccessController.Access.FULL;
        }
        /*
         * Accept remote admin requests that are authenticated with username/password
         * only if secure admin is enabled.
         */
        if ( ! NetUtils.isThisHostLocal(originHost) &&
             ! SecureAdmin.Util.isEnabled(secureAdmin) ) {
            logger.log(Level.INFO,
                    lsm.getLocalString("remote.login.while.secure.admin.disabled",
                        "Remote admin log-in attempt from host {0} with username \"{1}\" rejected because secure admin is disabled",
                        originHost, user));
            return AdminAccessController.Access.NONE;
        }
        if (as.usesFileRealm()) {
            result = handleFileRealm(user, password);
            logger.log(Level.FINE, "Not a \"trusted sender\"; file realm user authentication {1} for admin user {0}",
                    new Object[] {user, result ? "passed" : "failed"});
            final Access access;
            if (result) {
                 access = chooseAccess();
                logger.log(Level.FINE, "Authorized {0} access for user {1}",
                        new Object[] {access, user});

            } else {
                access = Access.NONE;
            }
            return access;
        } else {
            //now, deleate to the security service
            ClassLoader pc = null;
            boolean hack = false;
            boolean authenticated = false;
            try {
                pc = Thread.currentThread().getContextClassLoader();
                if (!sc.getCommonClassLoader().equals(pc)) { //this is per Sahoo
                    Thread.currentThread().setContextClassLoader(sc.getCommonClassLoader());
                    hack = true;
                }
                Inhabitant<SecurityLifecycle> sl = habitat.getInhabitantByType(SecurityLifecycle.class);
                sl.get();
                snif.setup(System.getProperty(SystemPropertyConstants.INSTALL_ROOT_PROPERTY) + "/modules/security", Logger.getAnonymousLogger());
                LoginContextDriver.login(user, password.toCharArray(), realm);
                authenticated = true;
                final boolean isConsideredInAdminGroup = (
                        (as.getAssociatedAuthRealm().getGroupMapping() == null)
                        || ensureGroupMembership(user, realm));
                return isConsideredInAdminGroup
                    ?  (serverEnv.isDas() ? AdminAccessController.Access.FULL :
                        AdminAccessController.Access.MONITORING)
                    : AdminAccessController.Access.NONE;
           } catch(Exception e) {
//              LoginException le = new LoginException("login failed!");
//              le.initCause(e);
//              thorw le //TODO need to work on this, this is rather too ugly
                return AdminAccessController.Access.NONE;
           } finally {
                if (hack)
                    Thread.currentThread().setContextClassLoader(pc);
            }
        }
    }

    /**
     * Chooses what level of admin access to grant an authenticated user.
     * <p>
     * Currently we grant full access if this is the DAS and monitoring-only
     * access otherwise.
     * 
     * @return the access to be granted to the authenticated user
     */
    private Access chooseAccess() {
        return serverEnv.isDas()
                        ? AdminAccessController.Access.FULL
                        : AdminAccessController.Access.MONITORING;
    }

    /**
     * Tries to authenticate using a Principal, typically from the incoming admin request,
     * or using the special admin indicator (which flags requests as from another
     * server in the unsecured use case).
     *
     * @param authRelatedHeaders headers related to authentication from the incoming admin request
     * @param reqPrincipal Principal, typically as returned by the secure transport which delivered the incoming admin request
     * @return true if the Principal is non-null and is the expected one and is therefore authorized for admin tasks; false otherwise
     * @throws Exception
     */
    private boolean authenticateAsTrustedSender(
            final Map<String,String> authRelatedHeaders, final Principal reqPrincipal) throws LoginException  {
        /*
         * If secure admin is enabled, use only the cert check.  If it's not
         * enabled, use only the special indicator check.
         */
        boolean result;
        result = authenticateUsingCert(reqPrincipal, 
                serverEnv.isDas() ? SecureAdmin.Util.instanceAlias(secureAdmin) :
                    SecureAdmin.Util.DASAlias(secureAdmin));
        if (result) {
            logger.log(Level.FINE, "Authenticated SSL client auth principal {0}", reqPrincipal.getName());
            return result;
        }
        if ( ! SecureAdmin.Util.isEnabled(secureAdmin)) {
            result = authenticateUsingSpecialIndicator(
                    authRelatedHeaders.get(SecureAdmin.Util.ADMIN_INDICATOR_HEADER_NAME));
            if (result) {
                logger.log(Level.FINE, "Authenticated server using server admin header indicator");
                return result;
            }
        }
        
        result = authenticateUsingOneTimeToken(
                authRelatedHeaders.get(SecureAdmin.Util.ADMIN_ONE_TIME_AUTH_TOKEN_HEADER_NAME));
        if (result) {
            logger.log(Level.FINE, "Authenticated using one-time auth token");
        }
        return result;
    }

    private boolean authenticateUsingCert(final Principal reqPrincipal, final String instanceAlias) throws LoginException {
        if (reqPrincipal == null) {
            return false;
        }
        try {
            final Principal expectedPrincipal = expectedPrincipal(instanceAlias);
            return (expectedPrincipal != null ? expectedPrincipal.equals(reqPrincipal) : false);
        } catch (Exception ex) {
            final LoginException loginEx = new LoginException();
            loginEx.initCause(ex);
            throw loginEx;
        }
    }

    private synchronized KeyStore trustStore() throws IOException {
        if (truststore == null) {
            truststore = sslUtils().getTrustStore();
        }
        return truststore;
    }

    private synchronized SSLUtils sslUtils() {
        if (sslUtils == null) {
            sslUtils = habitat.getComponent(SSLUtils.class);
        }
        return sslUtils;
    }

    private synchronized Principal expectedPrincipal(final String instanceAlias) throws IOException, KeyStoreException {
        Principal result;
        if ((result = serverPrincipals.get(instanceAlias)) == null) {
            final Certificate cert = trustStore().getCertificate(instanceAlias);
            if (cert == null || ! (cert instanceof X509Certificate)) {
                return null;
            }
            result = ((X509Certificate) cert).getSubjectX500Principal();
            serverPrincipals.put(instanceAlias, result);
        }
        return result;
    }

    /*
     * Returns whether the admin indicator value tells that the sender is
     * a server (DAS or instance).
     */
    private boolean authenticateUsingSpecialIndicator(
            final String candidateSpecialAdminIndicator) {
        if (candidateSpecialAdminIndicator == null) {
            return false;
        }
        return candidateSpecialAdminIndicator.equals(
                SecureAdmin.Util.configuredAdminIndicator(secureAdmin));
    }

    private boolean authenticateUsingOneTimeToken(
            final String oneTimeAuthToken) {
        return oneTimeAuthToken == null ? false : authTokenManager.consumeToken(oneTimeAuthToken);
    }


    private boolean ensureGroupMembership(String user, String realm) {
        try {
            SecurityContext sc = SecurityContext.getCurrent();
            Set ps = sc.getPrincipalSet(); //before generics
            for (Object principal : ps) {
                if (principal instanceof Group) {
                    Group group = (Group) principal;
                    if (group.getName().equals(AdminConstants.DOMAIN_ADMIN_GROUP_NAME))
                        return true;
                }
            }
            logger.fine("User is not the member of the special admin group");
            return false;
        } catch(Exception e) {
            logger.fine("User is not the member of the special admin group: " + e.getMessage());
            return false;
        }

    }

    private boolean handleFileRealm(String user, String password) throws LoginException {
        /* I decided to handle FileRealm  as a special case. Maybe it is not such a good idea, but
           loading the security subsystem for FileRealm is really not required.
         * If no user name was supplied, assume the default admin user name,
         * if there is one.
         */
        if (user == null || user.length() == 0) {
            String defuser = getDefaultAdminUser();
            if (defuser != null) {
                user = defuser;
                logger.fine("Using default user: " + defuser);
            } else
                logger.fine("No default user");
        }

        try {
            AuthRealm ar = as.getAssociatedAuthRealm();
            if (FileRealm.class.getName().equals(ar.getClassname())) {
                String adminKeyFilePath = ar.getPropertyValue("file");
                FileRealm fr = new FileRealm(adminKeyFilePath);
                FileRealmUser fru = (FileRealmUser)fr.getUser(user);
                for (String group : fru.getGroups()) {
                    if (group.equals(AdminConstants.DOMAIN_ADMIN_GROUP_NAME))
                        return fr.authenticate(user, password.toCharArray()) != null; //this is indirect as all admin-keyfile users are in group "asadmin"
                }
                return false;
            }
        } catch(NoSuchUserException ue) {
            return false;       // if fr.getUser fails to find the user name
        } catch(Exception e) {
            LoginException le =  new LoginException (e.getMessage());
            le.initCause(e);
            throw le;
        }
        return false;
    }

    /**
     * Return the default admin user.  A default admin user only
     * exists if the admin realm is a file realm and the file
     * realm contains exactly one user.  If so, that's the default
     * admin user.
     */
    private String getDefaultAdminUser() {
        AuthRealm realm = as.getAssociatedAuthRealm();
        if (realm == null) {
            //this is really an assertion -- admin service's auth-realm-name points to a non-existent realm
            throw new RuntimeException("Warning: Configuration is bad, realm: " + as.getAuthRealmName() + " does not exist!");
        }
        if (! FileRealm.class.getName().equals(realm.getClassname())) {
            logger.fine("CAN'T FIND DEFAULT ADMIN USER: IT'S NOT A FILE REALM");
            return null;  // can only find default admin user in file realm
        }
        String pv = realm.getPropertyValue("file");  //the property named "file"
        File   rf = null;
        if (pv == null || !(rf=new File(pv)).exists()) {
            //an incompletely formed file property or the file property points to a non-existent file, can't allow access
            logger.fine("CAN'T FIND DEFAULT ADMIN USER: THE KEYFILE DOES NOT EXIST");
            return null;
        }
        try {
            FileRealm fr = new FileRealm(rf.getAbsolutePath());
            Enumeration users = fr.getUserNames();
            if (users.hasMoreElements()) {
                String au = (String) users.nextElement();
                if (!users.hasMoreElements()) {
                    FileRealmUser fru = (FileRealmUser)fr.getUser(au);
                    for (String group : fru.getGroups()) {
                        if (group.equals(AdminConstants.DOMAIN_ADMIN_GROUP_NAME))
                            // there is only one admin user, in the right group, default to it
                            logger.fine("Attempting access using default admin user: " + au);
                            return au;
                    }
                }
            }
        } catch(Exception e) {
            return null;
        }
        return null;
    }

    /**
     * Check whether the password is the local password.
     * We ignore the user name but could check whether it's
     * a valid admin user name.
     */
    private boolean isLocalPassword(String user, String password) {
        if (!localPassword.isLocalPassword(password)) {
            logger.finest("Password is not the local password");
            return false;
        }
        logger.fine("Allowing access using local password");
        return true;
    }

    /**
     * The JMXAUthenticator's authenticate method.
     */
    @Override
    public Subject authenticate(Object credentials) {
        String user = "", password = "";
        String host = null;
        if (credentials instanceof String[]) {
            // this is supposed to be 2-string array with user name and password
            String[] up = (String[])credentials;
            if (up.length == 1) {
                user = up[0];
            } else if (up.length >= 2) {
                user = up[0];
                password = up[1];
                if (password == null)
                    password = "";
            }
            if (up.length > 2) {
                host = up[2];
            }
        }

        String realm = as.getSystemJmxConnector().getAuthRealmName(); //yes, for backward compatibility;
        if (realm == null)
            realm = as.getAuthRealmName();

        try {
            AdminAccessController.Access result = this.loginAsAdmin(user, password, realm, host);
            if (result == AdminAccessController.Access.NONE) {
                String msg = lsm.getLocalString("authentication.failed",
                        "User [{0}] does not have administration access", user);
                throw new SecurityException(msg);
            }
            // TODO Do we need to build a Subject so JMX can enforce monitor-only vs. manage permissions?
            return null; //for now;
        } catch (LoginException e) {
            throw new SecurityException(e);
        }
    }
}
