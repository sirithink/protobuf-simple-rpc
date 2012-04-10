package com.orbekk.protobuf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

public class ConnectionHandler {
    private final Socket connection;
    private final BlockingQueue<Data.Response> dispatcherOutput;
    private final RequestDispatcher dispatcher;

    private class IncomingHandler implements Runnable {
        @Override public void run() {
            dispatcher.start();
            try {
                BufferedInputStream input = new BufferedInputStream(
                        connection.getInputStream());
                while (!connection.isClosed()) {
                    Data.Request r = Data.Request.parseDelimitedFrom(
                            input);
                    if (r == null) {
                        tryCloseConnection();
                    } else {
                        try {
                            dispatcher.handleRequest(r);
                        } catch (InterruptedException e) {
                            tryCloseConnection();
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                tryCloseConnection();
            }
            dispatcher.interrupt();
        }
    }

    private class OutgoingHandler implements Runnable {
        @Override public void run() {
            try {
                BufferedOutputStream output = new BufferedOutputStream(
                        connection.getOutputStream());
                LinkedList<Data.Response> buffer =
                        new LinkedList<Data.Response>();
                while (!connection.isClosed()) {
                    buffer.clear();
                    buffer.add(dispatcherOutput.take());
                    dispatcherOutput.drainTo(buffer);
                    for (Data.Response response : buffer) {
                        response.writeDelimitedTo(output);
                    }
                    output.flush();
                }
            } catch (InterruptedException e) {
                tryCloseConnection();
            } catch (IOException e) {
                tryCloseConnection();
            }
            dispatcher.interrupt();
        }
    }
    
    public static ConnectionHandler create(Socket connection,
            ExecutorService requestPool, ServiceHolder services) {
        BlockingQueue<Data.Response> dispatcherOutput =
                new ArrayBlockingQueue(RequestDispatcher.DEFAULT_QUEUE_SIZE);
        RequestDispatcher dispatcher = new RequestDispatcher(
                requestPool, dispatcherOutput, services);
        return new ConnectionHandler(connection, dispatcherOutput,
                dispatcher);
    }
    
    ConnectionHandler(Socket connection,
            BlockingQueue<Data.Response> dispatcherOutput,
            RequestDispatcher dispatcher) {
        this.connection = connection;
        this.dispatcherOutput = dispatcherOutput;
        this.dispatcher = dispatcher;
    }

    public void closeConnection() {
        tryCloseConnection();
    }
    
    private void tryCloseConnection() {
        try {
            connection.close();
        } catch (IOException e) {
            // Assume connection is closed.
        }
    }
    
    public Runnable createIncomingHandler() {
        return new IncomingHandler();
    }
    
    public Runnable createOutgoingHandler() {
        return new OutgoingHandler();
    }
}
