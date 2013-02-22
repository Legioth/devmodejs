package com.github.legioth.devmode.proxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.handler.AbstractHandler;

import com.google.gwt.dev.shell.BrowserChannel;
import com.google.gwt.dev.shell.BrowserChannel.SessionHandler.SpecialDispatchId;
import com.google.gwt.dev.shell.BrowserChannel.Value.ValueType;
import com.google.gwt.dev.shell.RemoteObjectTable;

public class XhrProxy extends BrowserChannel {

    // static class copied from BrowserChannelClient
    private static class ClientObjectRefFactory implements ObjectRefFactory {

        private final RemoteObjectTable<JavaObjectRef> remoteObjectTable;

        public ClientObjectRefFactory() {
            remoteObjectTable = new RemoteObjectTable<JavaObjectRef>();
        }

        @Override
        public JavaObjectRef getJavaObjectRef(int refId) {
            JavaObjectRef objectRef = remoteObjectTable
                    .getRemoteObjectRef(refId);
            if (objectRef == null) {
                objectRef = new JavaObjectRef(refId);
                remoteObjectTable.putRemoteObjectRef(refId, objectRef);
            }
            return objectRef;
        }

        @Override
        public JsObjectRef getJsObjectRef(int refId) {
            return new JsObjectRef(refId);
        }

        @Override
        public Set<Integer> getRefIdsForCleanup() {
            return remoteObjectTable.getRefIdsForCleanup();
        }
    }

    public XhrProxy(Socket socket) throws IOException {
        super(socket, new ClientObjectRefFactory());
    }

    public static void main(String[] args) throws Exception {
        // TODO hardcoded values
        final int shutdownPort = 5674;
        final int httpPort = 1234;

        try {
            // Try to notify another instance that it's time to close
            Socket socket = new Socket((String) null, shutdownPort);
            // Wait until the other instance says it has closed
            socket.getInputStream().read();
            // Then tidy up
            socket.close();
        } catch (IOException e) {
            // Ignore if port is not open
        }

        final Server server = new Server();
        SocketConnector connector = new SocketConnector();
        connector.setPort(httpPort);
        server.addConnector(connector);

        final HashMap<String, XhrProxy> proxies = new HashMap<String, XhrProxy>();

        Handler handler = new AbstractHandler() {
            @Override
            public void handle(String path, HttpServletRequest request,
                    HttpServletResponse response, int dispatch)
                    throws IOException, ServletException {
                String pathInfo = request.getPathInfo();
                if ("/devmode.js".equals(pathInfo)) {
                    // Quick and dirty static file serving
                    serveDevmodeJsFile(response);
                    return;
                } else if ("/favicon.ico".equals(pathInfo)) {
                    // Implicit 404
                    return;
                }

                if (request.getMethod().equals("OPTIONS")) {
                    setCorsHeaders(request, response);
                    response.setStatus(200);
                    response.getOutputStream().close();
                    return;
                }

                String sessionId = request.getParameter("GwtSession");
                if (sessionId == null || sessionId.isEmpty()) {
                    throw new RuntimeException("No session id in request "
                            + request.getRequestURI());
                }

                XhrProxy proxy = findProxy(sessionId);

                ServletInputStream dataStream = request.getInputStream();
                String data = IOUtils.toString(dataStream);
                dataStream.close();
                try {
                    System.out.println("Client sent " + data);
                    JSONArray message = new JSONArray(data);

                    JSONArray result = proxy.processMessage(message);
                    System.out.println("Returning to client "
                            + result.toString());

                    setCorsHeaders(request, response);
                    response.setStatus(200);
                    PrintWriter writer = response.getWriter();
                    writer.print(result.toString());
                    writer.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private void serveDevmodeJsFile(HttpServletResponse response)
                    throws IOException {
                InputStream resourceAsStream = XhrProxy.class
                        .getResourceAsStream("../devmode.js");
                if (resourceAsStream == null) {
                    throw new RuntimeException(
                            "Can not find devmode.js from the classpath");
                }

                response.setStatus(200);
                response.setContentType("text/javascript");

                ServletOutputStream outputStream = response.getOutputStream();
                IOUtils.copy(resourceAsStream, outputStream);
                outputStream.close();

                return;
            }

            private void setCorsHeaders(HttpServletRequest request,
                    HttpServletResponse response) {
                response.setHeader("Access-Control-Allow-Origin", "*");

                String headersRequest = request
                        .getHeader("Access-Control-Request-Headers");
                if (headersRequest != null && !headersRequest.isEmpty()) {
                    response.setHeader("Access-Control-Allow-Headers",
                            headersRequest);
                }

            }

            private XhrProxy findProxy(String sessionId) throws IOException {
                synchronized (proxies) {
                    XhrProxy xhrProxy = proxies.get(sessionId);
                    if (xhrProxy == null) {
                        // TODO only kill stale sessions, support multiple
                        // sessions
                        killAllProxies(proxies);

                        // TODO hardcoded stuff
                        Socket socket = new Socket("localhost", 9997);
                        socket.setTcpNoDelay(true);
                        xhrProxy = new XhrProxy(socket);
                        proxies.put(sessionId, xhrProxy);
                    }
                    return xhrProxy;
                }
            }
        };
        server.addHandler(handler);

        try {
            server.start();

            final ServerSocket serverSocket = new ServerSocket(shutdownPort, 1,
                    InetAddress.getByName("127.0.0.1"));
            new Thread() {
                @Override
                public void run() {
                    try {
                        System.out
                                .println("Waiting for shutdown signal on port "
                                        + serverSocket.getLocalPort());
                        // Start waiting for a close signal
                        Socket accept = serverSocket.accept();
                        // First stop listening to the port
                        serverSocket.close();
                        System.out
                                .println("Got shutdown signal, killing server");
                        killAllProxies(proxies);
                        // Then stop the jetty server
                        server.stop();
                        // Send a byte to tell the other process that it can
                        // start jetty
                        OutputStream outputStream = accept.getOutputStream();
                        outputStream.write(0);
                        outputStream.flush();
                        // Finally close the socket
                        accept.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }.start();

            server.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void killAllProxies(final HashMap<String, XhrProxy> proxies)
            throws IOException {
        Collection<XhrProxy> values = proxies.values();
        for (XhrProxy value : values) {
            DataInputStream stream = value.getStreamFromOtherSide();
            while (stream.available() > 0) {
                stream.read();
            }
            value.endSession();
        }
        values.clear();
    }

    private int messageCount = 0;

    protected JSONArray processMessage(JSONArray message) throws Exception {
        messageCount++;
        if (messageCount % 100 == 0) {
            System.out.println("Processed " + messageCount + " requests");
        }
        MessageType type = MessageType.values()[message.getInt(0)];

        switch (type) {
        case CHECK_VERSIONS:
            return forwardVersionMessage(message);
        case LOAD_MODULE:
            forwardLoadModule(message);
            return waitForTrouble();
        case RETURN:
            forwardReturnMessage(message);
            return waitForTrouble();
        case INVOKE:
            forwardInvokeMessage(message);
            return waitForTrouble();
        case INVOKE_SPECIAL:
            forwardInvokeSpecial(message);
            return waitForTrouble();
        default:
            throw new RuntimeException("Unsupported message type: " + type);
        }
    }

    private void forwardInvokeSpecial(JSONArray message) throws Exception {
        SpecialDispatchId dispatchId = SpecialDispatchId.values()[message
                .getInt(1)];
        Value[] args = new Value[message.getInt(2)];
        for (int i = 0; i < args.length; i++) {
            Object value = message.get(i + 3);
            if (value instanceof JSONArray) {
                args[i] = jsonToValue((JSONArray) value);
            } else {
                args[i] = new Value(message.getInt(i + 3));
            }
        }

        new InvokeSpecialMessage(this, dispatchId, args).send();
    }

    private void forwardInvokeMessage(JSONArray message) throws Exception {
        JSONArray jsonArgs = message.getJSONArray(3);
        Value[] args = new Value[jsonArgs.length()];
        for (int i = 0; i < args.length; i++) {
            args[i] = jsonToValue(jsonArgs.getJSONArray(i));
        }
        new InvokeOnServerMessage(this, message.getInt(1),
                jsonToValue(message.getJSONArray(2)), args).send();
    }

    private void forwardReturnMessage(JSONArray message) throws Exception {
        new ReturnMessage(this, message.getInt(1) != 0,
                jsonToValue(message.getJSONArray(2))).send();
    }

    private Value jsonToValue(JSONArray data) throws JSONException {
        ValueType type = ValueType.values()[data.getInt(0)];
        Value value = new Value();
        switch (type) {
        case UNDEFINED:
            value.setUndefined();
            break;
        case NULL:
            value.setNull();
            break;
        case BOOLEAN:
            value.setBoolean(data.getBoolean(1));
            break;
        case DOUBLE:
            value.setDouble(data.getDouble(1));
            break;
        case JS_OBJECT:
            value.setJsObject(new JsObjectRef(data.getInt(1)));
            break;
        case STRING:
            value.setString(data.getString(1));
            break;
        case JAVA_OBJECT:
            value.setJavaObject(new JavaObjectRef(data.getInt(1)));
            break;
        default:
            throw new RuntimeException("Unsupported value type: " + type);
        }
        return value;
    }

    private JSONArray waitForTrouble() throws Exception {
        JSONArray response = new JSONArray();
        while (true) {
            MessageType type = Message
                    .readMessageType(getStreamFromOtherSide());
            switch (type) {
            case REQUEST_ICON:
                RequestIconMessage.receive(this);
                // Not using any icons
                UserAgentIconMessage.send(this, null);
                break;
            case LOAD_JSNI:
                LoadJsniMessage loadJsniMessage = LoadJsniMessage.receive(this);
                response.put(new Object[] { type.getId(),
                        loadJsniMessage.getJsni() });
                System.out.println("Loading JSNI:\n"
                        + loadJsniMessage.getJsni().replaceAll("\\n", "\n"));
                if (!loadJsniMessage.isAsynchronous()) {
                    return response;
                }
                break;
            case INVOKE:
                InvokeOnClientMessage invokeOnClientMessage = InvokeOnClientMessage
                        .receive(this);
                Value[] args = invokeOnClientMessage.getArgs();
                Object[] arguments = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    arguments[i] = valueToJson(args[i]);
                }
                response.put(new Object[] { type.getId(),
                        invokeOnClientMessage.getMethodName(),
                        valueToJson(invokeOnClientMessage.getThis()), arguments });
                if (!invokeOnClientMessage.isAsynchronous()) {
                    return response;
                }
                break;
            case RETURN:
                ReturnMessage returnMessage = ReturnMessage.receive(this);
                response.put(new Object[] { type.getId(),
                        returnMessage.isException() ? 1 : 0,
                        valueToJson(returnMessage.getReturnValue()) });
                if (!returnMessage.isAsynchronous()) {
                    return response;
                }
                break;
            case FREE_VALUE:
                FreeMessage freeMessage = FreeMessage.receive(this);
                int[] ids = freeMessage.getIds();
                response.put(new Object[] { type.getId(), ids });
                if (!freeMessage.isAsynchronous()) {
                    return response;
                }
                break;
            default:
                throw new RuntimeException("Unsupported message type: " + type);
            }
        }
    }

    private Object valueToJson(Value value) {
        Object data = value.getValue();
        if (value.isJavaObject()) {
            data = value.getJavaObject().getRefid();
        } else if (value.isJsObject()) {
            data = value.getJsObject().getRefid();
        }
        // JSONObject takes care of the rest of the types
        return new Object[] { value.getType().ordinal(), data };
    }

    private void forwardLoadModule(JSONArray message) throws Exception {
        new LoadModuleMessage(this, message.getString(1), message.getString(2),
                message.getString(3), message.getString(4),
                message.getString(5)).send();
    }

    private JSONArray forwardVersionMessage(JSONArray message) throws Exception {
        CheckVersionsMessage versionsMessage = new CheckVersionsMessage(this,
                message.getInt(1), message.getInt(2), message.getString(3));
        versionsMessage.send();

        MessageType responseType = Message
                .readMessageType(getStreamFromOtherSide());
        if (responseType != MessageType.PROTOCOL_VERSION) {
            throw new RuntimeException("Expected PROTOCOL_VERSION, got "
                    + responseType);
        }
        ProtocolVersionMessage replyMessage = ProtocolVersionMessage
                .receive(this);
        return new JSONArray(new Object[] { responseType.getId(),
                replyMessage.getProtocolVersion() });
    }
}
