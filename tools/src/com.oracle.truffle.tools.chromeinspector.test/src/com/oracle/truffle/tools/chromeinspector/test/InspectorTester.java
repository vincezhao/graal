/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tools.chromeinspector.test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession;
import com.oracle.truffle.tools.chromeinspector.types.RemoteObject;

public final class InspectorTester {

    private final InspectExecThread exec;

    private InspectorTester(InspectExecThread exec) {
        this.exec = exec;
    }

    public static InspectorTester start(boolean suspend) throws InterruptedException {
        RemoteObject.resetIDs();
        TruffleExecutionContext.resetIDs();
        InspectExecThread exec = new InspectExecThread(suspend);
        exec.start();
        exec.initialized.acquire();
        return new InspectorTester(exec);
    }

    public void finish() throws InterruptedException {
        synchronized (exec) {
            exec.done = true;
            exec.notifyAll();
        }
        exec.join();
        RemoteObject.resetIDs();
        TruffleExecutionContext.resetIDs();
    }

    public long getContextId() {
        return exec.contextId;
    }

    public Future<Value> eval(Source source) {
        return exec.eval(source);
    }

    public void sendMessage(String message) {
        exec.inspect.onMessage(message);
    }

    public String getMessages(boolean waitForSome) throws InterruptedException {
        return getMessages(waitForSome, 0);
    }

    private String getMessages(boolean waitForSome, int maxLength) throws InterruptedException {
        synchronized (exec.receivedMessages) {
            String messages;
            do {
                messages = exec.receivedMessages.toString();
                if (waitForSome && messages.isEmpty()) {
                    exec.receivedMessages.wait();
                } else {
                    break;
                }
            } while (true);
            if (maxLength > 0 && messages.length() > maxLength) {
                exec.receivedMessages.delete(0, maxLength);
                messages = messages.substring(0, maxLength);
            } else {
                exec.receivedMessages.delete(0, exec.receivedMessages.length());
            }
            return messages;
        }
    }

    public boolean compareReceivedMessages(String initialMessages) throws InterruptedException {
        String messages = initialMessages;
        int length = initialMessages.length();
        String msg = "";
        while (!messages.equals(msg)) {
            try {
                msg = getMessages(true, length);
            } catch (InterruptedException iex) {
                throw (InterruptedException) new InterruptedException("Interrupted while '" + messages + "' remains to be received.").initCause(iex);
            }
            if (!messages.startsWith(msg)) {
                assertEquals(messages, msg);
                return false;
            }
            length -= msg.length();
            messages = messages.substring(msg.length());
            msg = "";
        }
        return true;
    }

    private static class InspectExecThread extends Thread implements InspectServerSession.MessageListener {

        private final boolean suspend;
        private Context context;
        private InspectServerSession inspect;
        private long contextId;
        private Source evalSource;
        private CompletableFuture<Value> evalValue;
        private boolean done = false;
        private final StringBuilder receivedMessages = new StringBuilder();
        private final Semaphore initialized = new Semaphore(0);

        InspectExecThread(boolean suspend) {
            super("Inspector Executor");
            this.suspend = suspend;
        }

        @Override
        public void run() {
            Engine engine = Engine.create();
            InspectorTestInstrument.suspend = suspend;
            inspect = engine.getInstruments().get(InspectorTestInstrument.ID).lookup(InspectServerSession.class);
            try {
                contextId = engine.getInstruments().get(InspectorTestInstrument.ID).lookup(Long.class);
                inspect.setMessageListener(this);
                context = Context.newBuilder().engine(engine).build();
                initialized.release();
                Source source = null;
                CompletableFuture<Value> valueFuture = null;
                do {
                    synchronized (this) {
                        if (evalSource != null) {
                            source = evalSource;
                            valueFuture = evalValue;
                            evalSource = null;
                            evalValue = null;
                        } else {
                            source = null;
                            valueFuture = null;
                            try {
                                wait();
                            } catch (InterruptedException ex) {
                            }
                        }
                    }
                    if (source != null) {
                        Value value = context.eval(source);
                        valueFuture.complete(value);
                    }
                } while (!done);
            } finally {
                inspect.dispose();
            }
        }

        private Future<Value> eval(Source source) {
            Future<Value> valueFuture;
            synchronized (this) {
                evalSource = source;
                valueFuture = evalValue = new CompletableFuture<>();
                notifyAll();
            }
            return valueFuture;
        }

        @Override
        public void sendMessage(String message) {
            synchronized (receivedMessages) {
                receivedMessages.append(message);
                receivedMessages.append('\n');
                receivedMessages.notifyAll();
            }
        }

    }
}
