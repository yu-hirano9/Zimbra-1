/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2006, 2007, 2008, 2009, 2010 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package org.jivesoftware.wildfire.handler;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.stringprep.Stringprep;
import org.jivesoftware.stringprep.StringprepException;
import org.jivesoftware.util.IMConfig;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.Log;
import org.jivesoftware.wildfire.*;
import org.jivesoftware.wildfire.auth.AuthFactory;
import org.jivesoftware.wildfire.auth.AuthToken;
import org.jivesoftware.wildfire.auth.UnauthorizedException;
import org.jivesoftware.wildfire.user.UserManager;
import org.jivesoftware.wildfire.user.UserNotFoundException;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.StreamError;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the TYPE_IQ jabber:iq:auth protocol (plain only). Clients
 * use this protocol to authenticate with the server. A 'get' query
 * runs an authentication probe with a given user name. Return the
 * authentication form or an error indicating the user is not
 * registered on the server.<p>
 *
 * A 'set' query authenticates with information given in the
 * authentication form. An authenticated session may reset their
 * authentication information using a 'set' query.
 *
 * <h2>Assumptions</h2>
 * This handler assumes that the request is addressed to the server.
 * An appropriate TYPE_IQ tag matcher should be placed in front of this
 * one to route TYPE_IQ requests not addressed to the server to
 * another channel (probably for direct delivery to the recipient).
 *
 * @author Iain Shigeoka
 */
public class IQAuthHandler extends IQHandler implements IQAuthInfo {

    private static boolean anonymousAllowed;

    private Element probeResponse;
    private IQHandlerInfo info;

    private UserManager userManager;
    private XMPPServer localServer;
    private SessionManager sessionManager;

    /**
     * Clients are not authenticated when accessing this handler.
     */
    public IQAuthHandler() {
        super("XMPP Authentication handler");
        info = new IQHandlerInfo("query", "jabber:iq:auth");

        probeResponse = DocumentHelper.createElement(QName.get("query", "jabber:iq:auth"));
        probeResponse.addElement("username");
        if (AuthFactory.isPlainSupported()) {
            probeResponse.addElement("password");
        }
        if (AuthFactory.isDigestSupported()) {
            probeResponse.addElement("digest");
        }
        probeResponse.addElement("resource");
        anonymousAllowed = IMConfig.XMPP_AUTH_ANONYMOUS.getBoolean();
    }

    public IQ handleIQ(IQ packet) throws UnauthorizedException, PacketException {
        ClientSession session = sessionManager.getSession(packet.getFrom());
        // If no session was found then answer an error (if possible)
        if (session == null) {
            Log.error("Error during authentication. Session not found in " +
                    sessionManager.getPreAuthenticatedKeys() +
                    " for key " +
                    packet.getFrom());
            // This error packet will probably won't make it through
            IQ reply = IQ.createResultIQ(packet);
            reply.setChildElement(packet.getChildElement().createCopy());
            reply.setError(PacketError.Condition.internal_server_error);
            return reply;
        }
        IQ response = null;
        try {
            Element iq = packet.getElement();
            Element query = iq.element("query");
            Element queryResponse = probeResponse.createCopy();
            if (IQ.Type.get == packet.getType()) {
                String username = query.elementTextTrim("username");
                if (username != null) {
                    queryResponse.element("username").setText(username);
                }
                response = IQ.createResultIQ(packet);
                response.setChildElement(queryResponse);
                // This is a workaround. Since we don't want to have an incorrect TO attribute
                // value we need to clean up the TO attribute and send directly the response.
                // The TO attribute will contain an incorrect value since we are setting a fake
                // JID until the user actually authenticates with the server.
                if (session.getStatus() != Session.STATUS_AUTHENTICATED) {
                    response.setTo((JID)null);
                }
            }
            // Otherwise set query
            else {
                if (query.elements().isEmpty()) {
                    // Anonymous authentication
                    response = anonymousLogin(session, packet);
                }
                else {
                    String username = query.elementTextTrim("username");
                    // Login authentication
                    String password = query.elementTextTrim("password");
                    String digest = null;
                    if (query.element("digest") != null) {
                        digest = query.elementTextTrim("digest").toLowerCase();
                    }

                    // If we're already logged in, this is a password reset
                    if (session.getStatus() == Session.STATUS_AUTHENTICATED) {
                        response = passwordReset(password, packet, username, session);
                    }
                    else {
                        // it is an auth attempt
                        response =
                                login(username, query, packet, response, password, session,
                                        digest);
                    }
                }
            }
        }
        catch (UserNotFoundException e) {
            response = IQ.createResultIQ(packet);
            response.setChildElement(packet.getChildElement().createCopy());
            response.setError(PacketError.Condition.not_authorized);
        }
        catch (UnauthorizedException e) {
            response = IQ.createResultIQ(packet);
            response.setChildElement(packet.getChildElement().createCopy());
            response.setError(PacketError.Condition.not_authorized);
        }
        // Send the response directly since we want to be sure that we are sending it back
        // to the correct session. Any other session of the same user but with different
        // resource is incorrect.
        session.process(response);
        return null;
    }

    private IQ login(String username, Element iq, IQ packet, IQ response, String password,
            ClientSession session, String digest) throws UnauthorizedException,
            UserNotFoundException
    {
        String resource = iq.elementTextTrim("resource");
        if (resource != null) {
            try {
                resource = Stringprep.resourceprep(resource);
            }
            catch (StringprepException e) {
                throw new IllegalArgumentException("Invalid resource: " + resource);
            }
        }
        else {
            // Answer a not_acceptable error since a resource was not supplied
            response = IQ.createResultIQ(packet);
            response.setChildElement(packet.getChildElement().createCopy());
            response.setError(PacketError.Condition.not_acceptable);
        }
        username = username.toLowerCase();
        if (username.indexOf('@') > 0) {
            assert(username.substring(username.indexOf('@')).equals(session.getServerName()));
        } else {
            username = username + '@' + session.getServerName();
        }
        
        // If a session already exists with the requested JID, then check to see
        // if we should kick it off or refuse the new connection
        if (sessionManager.isActiveRoute(username, resource)) {
            ClientSession oldSession;
            try {
                oldSession = sessionManager.getSession(username, resource);
                oldSession.incrementConflictCount();
                int conflictLimit = sessionManager.getConflictKickLimit();
                if (conflictLimit != SessionManager.NEVER_KICK && oldSession.getConflictCount() > conflictLimit) {
                    Connection conn = oldSession.getConnection();
                    if (conn != null) {
                        // Send a stream:error before closing the connection
                        StreamError error = new StreamError(StreamError.Condition.conflict);
                        conn.deliverRawText(error.toXML());
                        conn.close();
                    }
                }
                else {
                    response = IQ.createResultIQ(packet);
                    response.setChildElement(packet.getChildElement().createCopy());
                    response.setError(PacketError.Condition.forbidden);
                }
            }
            catch (Exception e) {
                Log.error("Error during login", e);
            }
        }
        // If the connection was not refused due to conflict, log the user in
        if (response == null) {
            AuthToken token = null;
            if (password != null && AuthFactory.isPlainSupported()) {
                token = AuthFactory.authenticate(username, password);
            }
            else if (digest != null && AuthFactory.isDigestSupported()) {
                token = AuthFactory.authenticate(username, session.getStreamID().toString(),
                        digest);
            }
            if (token == null) {
                throw new UnauthorizedException();
            }
            else {
                session.setAuthToken(token, userManager, resource);
                packet.setFrom(session.getAddress());
                response = IQ.createResultIQ(packet);
            }
        }
        return response;
    }

    private IQ passwordReset(String password, IQ packet, String username, Session session)
            throws UnauthorizedException
    {
        IQ response;
        if (password == null || password.length() == 0) {
            throw new UnauthorizedException();
        }
        else {
            try {
                userManager.getUser(username).setPassword(password);
                response = IQ.createResultIQ(packet);
                List<String> params = new ArrayList<String>();
                params.add(username);
                params.add(session.toString());
                Log.info(LocaleUtils.getLocalizedString("admin.password.update", params));
            }
            catch (UserNotFoundException e) {
                throw new UnauthorizedException();
            }
        }
        return response;
    }

    private IQ anonymousLogin(ClientSession session, IQ packet) {
        IQ response = IQ.createResultIQ(packet);
        if (anonymousAllowed) {
            session.setAnonymousAuth();
            response.setTo(session.getAddress());
            Element auth = response.setChildElement("query", "jabber:iq:auth");
            auth.addElement("resource").setText(session.getAddress().getResource());
        }
        else {
            response.setChildElement(packet.getChildElement().createCopy());
            response.setError(PacketError.Condition.forbidden);
        }
        return response;
    }

    public boolean isAnonymousAllowed() {
        return anonymousAllowed;
    }

    public void initialize(XMPPServer server) {
        super.initialize(server);
        localServer = server;
        userManager = server.getUserManager();
        sessionManager = server.getSessionManager();
    }

    public IQHandlerInfo getInfo() {
        return info;
    }
}