import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;

public class Main {

    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        // Parse "--directory <path>"
        String directoryArg = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--directory") && i + 1 < args.length) {
                directoryArg = args[i + 1];
            }
        }

        final File baseDir;
        if (directoryArg == null) {
            baseDir = null;
            System.out.println("No --directory provided; continuing without file support.");
        } else {
            try {
                File temp = new File(directoryArg).getCanonicalFile();
                if (!temp.isDirectory()) {
                    System.out.println("Provided directory is not valid.");
                    baseDir = null;
                } else {
                    baseDir = temp;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            ServerSocket serverSocket = new ServerSocket(4221);
            serverSocket.setReuseAddress(true);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket, baseDir)).start();
            }
        } catch (IOException e) {
            System.out.println("Server exception: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket, File baseDir) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream output = clientSocket.getOutputStream();

            while (true) {

                // --- REQUEST LINE ---
                String requestLine = reader.readLine();
                if (requestLine == null) break;
                if (requestLine.isEmpty()) continue;

                // --- HEADERS ---
                String headerLine;
                String userAgent = null;
                String acceptEncoding = null;
                String connectionHeader = null;
                int contentLength = 0;

                while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                    String lower = headerLine.toLowerCase();

                    if (lower.startsWith("user-agent:")) {
                        userAgent = headerLine.substring(headerLine.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(
                                    headerLine.substring(headerLine.indexOf(':') + 1).trim());
                        } catch (Exception ignored) {}
                    } else if (lower.startsWith("accept-encoding:")) {
                        acceptEncoding = headerLine.substring(headerLine.indexOf(':') + 1)
                                .trim().toLowerCase();
                    } else if (lower.startsWith("connection:")) {
                        connectionHeader = headerLine.substring(headerLine.indexOf(':') + 1)
                                .trim().toLowerCase();
                    }
                }

                // --- METHOD + PATH ---
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String path = parts.length > 1 ? parts[1] : "/";

                // Call handler
                boolean shouldClose = handleRequest(
                        method, path, userAgent, acceptEncoding,
                        contentLength, connectionHeader,
                        reader, output, baseDir
                );

                output.flush();

                if (shouldClose) break;
            }

        } catch (IOException ignored) {
        } finally {
            try { clientSocket.close(); } catch (IOException ignored2) {}
        }
    }

    private static boolean handleRequest(
            String method,
            String path,
            String userAgent,
            String acceptEncoding,
            int contentLength,
            String connectionHeader,
            BufferedReader reader,
            OutputStream output,
            File baseDir
    ) throws IOException {

        boolean clientRequestedClose =
                connectionHeader != null && connectionHeader.equals("close");

        // Helper: write response headers including optional Connection: close
        java.util.function.Function<String, String> addConnectionHeader =
                (originalHeaders) -> clientRequestedClose
                        ? originalHeaders + "Connection: close\r\n"
                        : originalHeaders;

        // ---------------------------------------------------
        // GET /
        // ---------------------------------------------------
        if (method.equals("GET") && path.equals("/")) {
            String headers = "HTTP/1.1 200 OK\r\n";
            headers = addConnectionHeader.apply(headers) + "\r\n";
            output.write(headers.getBytes());
            return clientRequestedClose;
        }

        // ---------------------------------------------------
        // GET /echo/{msg} with gzip support
        // ---------------------------------------------------
        if (method.equals("GET") && path.startsWith("/echo/")) {

            String msg = path.substring("/echo/".length());
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);

            boolean gzip = false;
            if (acceptEncoding != null) {
                for (String enc : acceptEncoding.split(",")) {
                    String token = enc.trim().split(";")[0];
                    if (token.equals("gzip")) {
                        gzip = true;
                        break;
                    }
                }
            }

            if (gzip) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gos = new GZIPOutputStream(baos);
                gos.write(body);
                gos.finish();
                byte[] compressed = baos.toByteArray();

                String headers =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Encoding: gzip\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: " + compressed.length + "\r\n";

                headers = addConnectionHeader.apply(headers) + "\r\n";

                output.write(headers.getBytes());
                output.write(compressed);
                return clientRequestedClose;

            } else {
                String headers =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: " + body.length + "\r\n";

                headers = addConnectionHeader.apply(headers) + "\r\n";

                output.write(headers.getBytes());
                output.write(body);
                return clientRequestedClose;
            }
        }

        // ---------------------------------------------------
        // GET /user-agent
        // ---------------------------------------------------
        if (method.equals("GET") && path.equals("/user-agent")) {
            if (userAgent == null) userAgent = "";
            byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + body.length + "\r\n";

            headers = addConnectionHeader.apply(headers) + "\r\n";

            output.write(headers.getBytes());
            output.write(body);
            return clientRequestedClose;
        }

        // ---------------------------------------------------
        // GET /files/{filename}
        // ---------------------------------------------------
        if (method.equals("GET") && path.startsWith("/files/")) {

            if (baseDir == null) {
                String resp = "HTTP/1.1 404 Not Found\r\n";
                resp = addConnectionHeader.apply(resp) + "\r\n";
                output.write(resp.getBytes());
                return clientRequestedClose;
            }

            String filename = path.substring("/files/".length());

            Path filePath;
            try {
                filePath = new File(baseDir, filename).getCanonicalFile().toPath();
            } catch (Exception e) {
                String resp = "HTTP/1.1 404 Not Found\r\n";
                resp = addConnectionHeader.apply(resp) + "\r\n";
                output.write(resp.getBytes());
                return clientRequestedClose;
            }

            if (!filePath.startsWith(baseDir.toPath()) ||
                    !Files.exists(filePath) ||
                    !Files.isRegularFile(filePath)) {

                String resp = "HTTP/1.1 404 Not Found\r\n";
                resp = addConnectionHeader.apply(resp) + "\r\n";
                output.write(resp.getBytes());
                return clientRequestedClose;
            }

            byte[] fileBytes = Files.readAllBytes(filePath);

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + fileBytes.length + "\r\n";

            headers = addConnectionHeader.apply(headers) + "\r\n";

            output.write(headers.getBytes());
            output.write(fileBytes);
            return clientRequestedClose;
        }

        // ---------------------------------------------------
        // POST /files/{filename}
        // ---------------------------------------------------
        if (method.equals("POST") && path.startsWith("/files/")) {

            if (baseDir == null) {
                String resp = "HTTP/1.1 404 Not Found\r\n";
                resp = addConnectionHeader.apply(resp) + "\r\n";
                output.write(resp.getBytes());
                return clientRequestedClose;
            }

            String filename = path.substring("/files/".length());

            int remaining = contentLength;
            StringBuilder sb = new StringBuilder();
            while (remaining > 0) {
                char[] buf = new char[Math.min(remaining, 8192)];
                int read = reader.read(buf);
                if (read == -1) break;
                sb.append(buf, 0, read);
                remaining -= read;
            }

            byte[] bodyBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

            Path filePath = new File(baseDir, filename).getCanonicalFile().toPath();

            if (!filePath.startsWith(baseDir.toPath())) {
                String resp = "HTTP/1.1 404 Not Found\r\n";
                resp = addConnectionHeader.apply(resp) + "\r\n";
                output.write(resp.getBytes());
                return clientRequestedClose;
            }

            Files.write(filePath, bodyBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String headers = "HTTP/1.1 201 Created\r\n";
            headers = addConnectionHeader.apply(headers) + "\r\n";

            output.write(headers.getBytes());
            return clientRequestedClose;
        }

        // ---------------------------------------------------
        // FALLBACK → 404
        // ---------------------------------------------------
        String resp = "HTTP/1.1 404 Not Found\r\n";
        resp = addConnectionHeader.apply(resp) + "\r\n";
        output.write(resp.getBytes());

        return clientRequestedClose;
    }
}
