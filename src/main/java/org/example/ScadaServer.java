package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ScadaServer extends WebSocketServer {

    private static final Map<String, ControllerData> controllers = new ConcurrentHashMap<>();
    private static final int WS_PORT = 8080;
    private static final int REST_PORT = 8081;

    public ScadaServer(int port) {
        super(new InetSocketAddress(port));
    }

    public static void main(String[] args) throws IOException {
        // Инициализация контроллеров
        controllers.put("ctrl1", new ControllerData());
        controllers.put("ctrl2", new ControllerData());
        controllers.put("ctrl3", new ControllerData());

        ScadaServer server = new ScadaServer(WS_PORT);
        server.start();

        startHttpServer();

        Timer timer = new Timer();
        Random random = new Random();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                for (Map.Entry<String, ControllerData> entry : controllers.entrySet()) {
                    ControllerData data = entry.getValue();
                    if (data.enabled) {
                        data.temperature = 20 + random.nextDouble() * 10;
                        data.level = random.nextDouble() * 100;
                    }

                    String json = String.format(Locale.US,
                            "{\"controller\": \"%s\", \"temperature\": %.2f, \"level\": %.2f, \"enabled\": %s}",
                            entry.getKey(), data.temperature, data.level, data.enabled);

                    for (WebSocket conn : server.getConnections()) {
                        conn.send(json);
                    }
                }
            }
        }, 0, 1000);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Клиент подключён: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Клиент отключён: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("Сообщение от клиента: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("SCADA WebSocket сервер запущен на порту " + WS_PORT);
    }

    private static void startHttpServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(REST_PORT), 0);

        httpServer.createContext("/all", exchange -> {
            String response = controllersToJson();
            sendResponse(exchange, 200, response);
        });

        httpServer.createContext("/controller/set", new ControllerSetHandler());
        httpServer.createContext("/controller/state", new ControllerStateHandler());

        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("REST API сервер запущен на порту " + REST_PORT);
    }

    private static class ControllerSetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> params = parseForm(exchange);
            String id = params.get("id");
            double temp = Double.parseDouble(params.getOrDefault("temperature", "0"));
            double level = Double.parseDouble(params.getOrDefault("level", "0"));

            ControllerData data = controllers.get(id);
            if (data != null) {
                data.temperature = temp;
                data.level = level;
                sendResponse(exchange, 200, "{\"status\": \"ok\"}");
            } else {
                sendResponse(exchange, 404, "{\"error\": \"controller not found\"}");
            }
        }
    }

    private static class ControllerStateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> params = parseForm(exchange);
            String id = params.get("id");
            boolean enable = Boolean.parseBoolean(params.getOrDefault("enable", "false"));

            ControllerData data = controllers.get(id);
            if (data != null) {
                data.enabled = enable;
                sendResponse(exchange, 200, "{\"status\": \"ok\"}");
            } else {
                sendResponse(exchange, 404, "{\"error\": \"controller not found\"}");
            }
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))
                .lines().collect(Collectors.joining());
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                result.put(URLDecoder.decode(parts[0], "UTF-8"),
                        URLDecoder.decode(parts[1], "UTF-8"));
            }
        }
        return result;
    }

    private static String controllersToJson() {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, ControllerData> entry : controllers.entrySet()) {
            ControllerData d = entry.getValue();
            sb.append("\"").append(entry.getKey()).append("\": {");
            sb.append("\"temperature\":").append(String.format(Locale.US, "%.2f", d.temperature)).append(",");
            sb.append("\"level\":").append(String.format(Locale.US, "%.2f", d.level)).append(",");
            sb.append("\"enabled\":").append(d.enabled);
            sb.append("}");
            if (++i < controllers.size()) sb.append(",");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static class ControllerData {
        double temperature = 0.0;
        double level = 0.0;
        boolean enabled = true;
    }
}