/*
 * Copyright (c) 2002-2018 Gargoyle Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gargoylesoftware.htmlunit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.gargoylesoftware.htmlunit.MockWebConnection.RawResponseData;
import com.gargoylesoftware.htmlunit.util.NameValuePair;

/**
 * Mini server simulating some not standard behaviors.
 *
 * @author Marc Guillemot
 * @author Frank Danek
 * @author Ronald Brill
 */
public class MiniServer extends Thread {
    private static final Log LOG = LogFactory.getLog(MiniServer.class);

    private final int port_;
    private volatile boolean shutdown_ = false;
    private final AtomicBoolean started_ = new AtomicBoolean(false);
    private final MockWebConnection mockWebConnection_;
    private volatile ServerSocket serverSocket_;
    private static final String DROP_CONNECTION = "#drop connectoin#";

    static void configureDropConnection(final MockWebConnection mockWebConnection, final URL url) {
        mockWebConnection.setResponse(url, DROP_CONNECTION);
    }

    MiniServer(final int port, final MockWebConnection mockWebConnection) {
        port_ = port;
        mockWebConnection_ = mockWebConnection;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            serverSocket_ = new ServerSocket(port_);
            started_.set(true);
            LOG.info("Starting listening on port " + port_);
            while (!shutdown_) {
                try (Socket s = serverSocket_.accept()) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

                        final CharBuffer cb = CharBuffer.allocate(5000);
                        br.read(cb);
                        cb.flip();
                        final String in = cb.toString();
                        cb.rewind();

                        final RawResponseData responseData = getResponseData(in);

                        if (responseData == null || responseData.getStringContent() == DROP_CONNECTION) {
                            LOG.info("Closing impolitely in & output streams");
                            s.getOutputStream().close();
                        }
                        else {
                            try (PrintWriter pw = new PrintWriter(s.getOutputStream())) {
                                pw.println("HTTP/1.0 " + responseData.getStatusCode() + " "
                                        + responseData.getStatusMessage());
                                for (final NameValuePair header : responseData.getHeaders()) {
                                    pw.println(header.getName() + ": " + header.getValue());
                                }
                                pw.println();
                                pw.println(responseData.getStringContent());
                                pw.println();
                                pw.flush();
                            }
                        }
                    }
                }
            }
        }
        catch (final SocketException e) {
            if (!shutdown_) {
                LOG.error(e);
            }
        }
        catch (final IOException e) {
            LOG.error(e);
        }
        finally {
            LOG.info("Finished listening on port " + port_);
        }
    }

    private RawResponseData getResponseData(final String in) {
        final WebRequest request = parseRequest(in);
        if (request == null) {
            return null;
        }

        try {
            return mockWebConnection_.getRawResponse(request);
        }
        catch (final IllegalStateException e) {
            LOG.error(e);
            return null;
        }
    }

    private WebRequest parseRequest(final String request) {
        final int firstSpace = request.indexOf(' ');
        final int secondSpace = request.indexOf(' ', firstSpace + 1);

        final String requestedPath = request.substring(firstSpace + 1, secondSpace);
        if ("/favicon.ico".equals(requestedPath)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Skipping /favicon.ico");
            }
            return null;
        }
        try {
            final URL url = new URL("http://localhost:" + port_ + requestedPath);
            return new WebRequest(url);
        }
        catch (final MalformedURLException e) {
            LOG.error(e);
            return null;
        }
    }

    /**
     * ShutDown this server.
     * @throws InterruptedException in case of error
     * @throws IOException in case of error
     */
    public void shutDown() throws InterruptedException, IOException {
        shutdown_ = true;
        serverSocket_.close();
        interrupt();
        join(5000);
    }

    @Override
    public synchronized void start() {
        super.start();

        // wait until the listener on the port has been started to be sure
        // that the main thread doesn't perform the first request before the listener is ready
        for (int i = 0; i < 10; i++) {
            if (!started_.get()) {
                try {
                    Thread.sleep(100);
                }
                catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
