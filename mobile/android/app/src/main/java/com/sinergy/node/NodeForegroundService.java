package com.sinergy.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class NodeForegroundService extends Service {
    private static final String TAG = "SINERGY_NODE";
    private static final String CHANNEL_ID = "sinergy_node_channel";
    private static final int NOTIF_ID = 1601;
    private static final int PORT = 8788; // Same as SINERGY_AGI
    
    private volatile boolean running = false;
    private Thread httpThread;
    private ServerSocket server;
    
    // AGI State
    private String nodeDid = "";
    private int blocks = 0;
    private long startTime = System.currentTimeMillis();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        
        // Initialize GGUF Engine
        try {
            LlamaBridge.init(this, "sinergy-agi-model.gguf");
            Log.i(TAG, "GGUF Engine initialized: " + LlamaBridge.getInfo());
        } catch (Exception e) {
            Log.w(TAG, "Using Java fallback: " + e.getMessage());
        }
        
        startForeground(NOTIF_ID, buildNotification("SINERGY AGI + L1 Active"));
        startLocalHttpServer();
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SINERGY Super App")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SINERGY Node Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startLocalHttpServer() {
        running = true;
        httpThread = new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                Log.i(TAG, "LLM Server started on port " + PORT);
                
                while (running) {
                    Socket client = server.accept();
                    handleClient(client);
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }, "sinergy-llm-server");
        httpThread.start();
    }

    private void handleClient(Socket client) {
        try (Socket c = client) {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            
            // Read request line
            String requestLine = br.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            
            // Parse method and path
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            
            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                String[] headerParts = line.split(": ", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0].toLowerCase(), headerParts[1]);
                }
            }
            
            // Read body if present
            String body = "";
            if (headers.containsKey("content-length")) {
                int contentLength = Integer.parseInt(headers.get("content-length"));
                if (contentLength > 0) {
                    char[] bodyChars = new char[contentLength];
                    br.read(bodyChars);
                    body = new String(bodyChars);
                }
            }
            
            // Handle request
            String response = processRequest(method, path, body);
            
            // Send response
            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + response.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n" +
                response;
            
            OutputStream out = c.getOutputStream();
            out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
            out.flush();
            
        } catch (Exception e) {
            Log.e(TAG, "Handle error: " + e.getMessage());
        }
    }

    private String processRequest(String method, String path, String body) {
        // Update L1 blocks
        blocks++;
        
        // API endpoints
        if (path.equals("/api/chat") || path.equals("/v1/chat/completions")) {
            return handleChat(body);
        } else if (path.equals("/api/status")) {
            return handleStatus();
        } else if (path.equals("/api/l1/blocks")) {
            return handleBlocks();
        } else if (path.equals("/api/agi/info")) {
            return handleAGIInfo();
        } else if (path.equals("/health")) {
            return "{\"status\":\"ok\",\"service\":\"sinergy-llm\"}";
        } else {
            return "{\"error\":\"Not found\",\"path\":\"" + path + "\"}";
        }
    }

    private String handleChat(String body) {
        try {
            // Parse JSON request
            String prompt = "";
            String model = "sinergy-agi";
            
            // Simple JSON parsing (without external libraries)
            if (body.contains("\"message\"")) {
                int msgStart = body.indexOf("\"message\"");
                int colon = body.indexOf(":", msgStart);
                int quote = body.indexOf("\"", colon + 1);
                int endQuote = body.indexOf("\"", quote + 1);
                if (colon > 0 && quote > 0 && endQuote > 0) {
                    prompt = body.substring(quote + 1, endQuote);
                }
            } else if (body.contains("\"messages\"")) {
                int lastUser = body.lastIndexOf("\"role\":\"user\"");
                if (lastUser >= 0) {
                    int contentStart = body.indexOf("\"content\":\"", lastUser);
                    if (contentStart >= 0) {
                        int start = contentStart + 12;
                        int end = body.indexOf("\"", start);
                        if (end > start) {
                            prompt = body.substring(start, end);
                        }
                    }
                }
            }
            
            if (prompt.isEmpty()) {
                prompt = "Привет";
            }
            
            // Generate response using GGUF Engine
            String response = LlamaBridge.generate(prompt);
            
            // Return in OpenAI-compatible format
            return "{\"id\":\"sinergy-\",\"object\":\"chat.completion\",\"created\":" + 
                   System.currentTimeMillis() / 1000 + 
                   ",\"model\":\"" + model + "\",\"choices\":[{\"index\":0," +
                   "\"message\":{\"role\":\"assistant\",\"content\":" + 
                   "\"" + response.replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}]}";
                   
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String handleStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return "{\"ok\":true,\"service\":\"sinergy-node\"," +
               "\"port\":" + PORT + "," +
               "\"uptime\":" + uptime + "s," +
               "\"blocks\":" + blocks + "," +
               "\"gguf\":\"" + LlamaBridge.getInfo().replace("\n", " ") + "\"," +
               "\"mode\":\"embedded\"}";
    }

    private String handleBlocks() {
        return "{\"blocks\":" + blocks + ",\"hash\":\"0x" + 
               Long.toHexString(blocks * 12345) + "\",\"timestamp\":" + 
               System.currentTimeMillis() + "}";
    }

    private String handleAGIInfo() {
        return "{\"did\":\"" + nodeDid + "\",\"service\":\"OLGA AGI\"," +
               "\"version\":\"1.0\"," +
               "\"engine\":\"GGUF\"," +
               "\"mode\":\"local\"," +
               "\"features\":[\"chat\",\"autopilot\",\"collective\"]}";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        LlamaBridge.cleanup();
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
