/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2007, 2008, 2009, 2010, 2011 Zimbra, Inc.
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

package com.zimbra.cs.imap;

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import static com.zimbra.cs.account.Provisioning.*;

import com.zimbra.common.util.Log;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.server.ServerConfig;
import com.zimbra.cs.util.BuildInfo;
import com.zimbra.cs.util.Config;

import java.util.Arrays;

public class ImapConfig extends ServerConfig {
    private static final String PROTOCOL = "IMAP4rev1";
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 100 * 1024 * 1024;

    public ImapConfig(boolean ssl) {
        super(PROTOCOL, ssl);
    }

    @Override
    public String getServerName() {
        return getAttr(A_zimbraImapAdvertisedName, LC.zimbra_server_hostname.value());
    }

    @Override
    public String getServerVersion() {
        return getBooleanAttr(A_zimbraImapExposeVersionOnBanner, false) ?
            BuildInfo.VERSION : null;
    }

    @Override
    public String getBindAddress() {
        return getAttr(isSslEnabled() ?
            A_zimbraImapSSLBindAddress : A_zimbraImapBindAddress, null);
    }

    @Override
    public int getBindPort() {
        return isSslEnabled() ?
            getIntAttr(A_zimbraImapSSLBindPort, Config.D_IMAP_SSL_BIND_PORT) :
            getIntAttr(A_zimbraImapBindPort, Config.D_IMAP_BIND_PORT);
    }

    @Override
    public int getNioMaxScheduledWriteBytes() {
        return LC.nio_imap_max_scheduled_write_bytes.intValue();
    }

    @Override
    public int getNioWriteTimeout() {
        return LC.nio_imap_write_timeout.intValue();
    }

    @Override
    public int getNioWriteChunkSize() {
        return LC.nio_imap_write_chunk_size.intValue();
    }

    @Override
    public int getMaxIdleSeconds() {
        return LC.imap_max_idle_time.intValue();
    }

    @Override
    public int getNumThreads() {
        return getIntAttr(A_zimbraImapNumThreads, super.getNumThreads());
    }

    @Override
    public Log getLog() {
        return ZimbraLog.imap;
    }

    @Override
    public String getConnectionRejected() {
        return "* BYE " + getDescription() + " closing connection; service busy";
    }

    @Override
    public int getShutdownGraceSeconds() {
       return getIntAttr(A_zimbraImapShutdownGraceSeconds, super.getShutdownGraceSeconds());
    }

    @Override
    public int getNioMinThreads() {
        return LC.nio_imap_min_threads.intValue();
    }

    @Override
    public int getNioThreadKeepAliveTime() {
        return LC.nio_imap_thread_keep_alive_time.intValue();
    }

    public int getAuthenticatedMaxIdleSeconds() {
        return LC.imap_authenticated_max_idle_time.intValue();
    }

    public boolean isCleartextLoginEnabled() {
        return getBooleanAttr(A_zimbraImapCleartextLoginEnabled, false);
    }

    public boolean isSaslGssapiEnabled() {
        return getBooleanAttr(A_zimbraImapSaslGssapiEnabled, false);
    }

    public boolean isCapabilityDisabled(String name) {
        String key = isSslEnabled() ?
            A_zimbraImapSSLDisabledCapability : A_zimbraImapDisabledCapability;
        try {
            return Arrays.asList(getLocalServer().getMultiAttr(key)).contains(name);
        } catch (ServiceException e) {
            getLog().warn("Unable to get server attribute: " + key, e);
            return false;
        }
    }

    public int getMaxRequestSize() {
        return getIntAttr(A_zimbraImapMaxRequestSize, LC.imap_max_request_size.intValue());
    }

    public long getMaxMessageSize() throws ServiceException {
        return Provisioning.getInstance().getConfig()
                .getLongAttr(A_zimbraMtaMaxMessageSize, DEFAULT_MAX_MESSAGE_SIZE);
    }
}
