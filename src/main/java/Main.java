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

        // Parse --directory <path>
        String directoryArg = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--directory") && i + 1 < args.length) {
                directoryArg = args[i + 1];
            }
        }

        final File baseDir;
        if (directoryArg == null) {
            baseDir = null;
            System.out.println("No --directory provided; continuing without file-serving support.");
        } else {
            File temp;
            try {
                temp = new File(directoryArg).getCanonicalFile();
                if (!temp.isDirectory()) {
                    baseDir = null;
                    System.out.println("Provided directory is not valid.");
                } else {
                    baseDir = temp;
                    System.out.println("Serving files from: " + baseDir.getAbsolutePath());
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
                System.out.println("accepted new connection");

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

                // ---------------------- READ REQUEST LINE ----------------------
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    break; // client closed connection
                }
                if (requestLine.isEmpty()) {
                    continue; // skip empty
                }

                System.out.println("Request Line: " + requestLine);

                // ---------------------- READ HEADERS --------------------------
                String headerLine;
                String userAgent = null;
                String acceptEncoding = null;
                int contentLength = 0;

                while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                    System.out.println("Header: " + headerLine);
                    String lower = headerLine.toLowerCase();

                    if (lower.startsWith("user-agent:")) {
                        userAgent = headerLine.substring(headerLine.indexOf(':') + 1).trim();

                    } else if (lower.startsWith("content-length:")) {
                        String val = headerLine.substring(headerLine.indexOf(':') + 1).trim();
                        try { contentLength = Integer.parseInt(val); } catch (Exception ignored) {}

                    } else if (lower.startsWith("accept-encoding:")) {
                        acceptEncoding = headerLine.substring(headerLine.indexOf(':') + 1).trim().toLowerCase();
                    }
                }

                // ---------------------- PARSE METHOD + PATH -------------------
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String path   = parts.length > 1 ? parts[1] : "/";

                // ---------------------- HANDLE REQUEST ------------------------
                handleRequest(method, path, userAgent, acceptEncoding,
                        contentLength, reader, output, baseDir);
            }

        } catch (IOException e) {
            System.out.println("Handler exception: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private static void handleRequest(
            String method,
            String path,
            String userAgent,
            String acceptEncoding,
            int contentLength,
            BufferedReader reader,
            OutputStream output,
            File baseDir
    ) throws IOException {

        // -----------------------------------------------------------
        // GET /
        // -----------------------------------------------------------
        if (method.equals("GET") && path.equals("/")) {
            String response = "HTTP/1.1 200 OK\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
            return;
        }

        // -----------------------------------------------------------
        // GET /echo/{msg}  (with gzip support)
        // -----------------------------------------------------------
        if (method.equals("GET") && path.startsWith("/echo/")) {

            String msg = path.substring("/echo/".length());
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);

            // Detect gzip support
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
                // ---- Compress ----
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gos = new GZIPOutputStream(baos);
                gos.write(body);
                gos.finish();
                byte[] compressed = baos.toByteArray();

                String headers =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Encoding: gzip\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: " + compressed.length + "\r\n" +
                                "\r\n";

                output.write(headers.getBytes(StandardCharsets.UTF_8));
                output.write(compressed);
                output.flush();
                return;

            } else {
                // ---- No compression ----
                String headers =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: " + body.length + "\r\n" +
                                "\r\n";

                output.write(headers.getBytes(StandardCharsets.UTF_8));
                output.write(body);
                output.flush();
                return;
            }
        }

        // -----------------------------------------------------------
        // GET /user-agent
        // -----------------------------------------------------------
        if (method.equals("GET") && path.equals("/user-agent")) {
            if (userAgent == null) userAgent = "";
            byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "\r\n";

            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
            return;
        }

        // -----------------------------------------------------------
        // GET /files/{filename}
        // -----------------------------------------------------------
        if (method.equals("GET") && path.startsWith("/files/")) {
            if (baseDir == null) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            String filename = path.substring("/files/".length());

            Path filePath;
            try {
                filePath = new File(baseDir, filename).getCanonicalFile().toPath();
            } catch (Exception e) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            if (!filePath.startsWith(baseDir.toPath())
                    || !Files.exists(filePath)
                    || !Files.isRegularFile(filePath)) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            byte[] fileBytes = Files.readAllBytes(filePath);

            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + fileBytes.length + "\r\n" +
                            "\r\n";

            output.write(headers.getBytes());
            output.write(fileBytes);
            output.flush();
            return;
        }

        // -----------------------------------------------------------
        // POST /files/{filename}
        // -----------------------------------------------------------
        if (method.equals("POST") && path.startsWith("/files/")) {
            if (baseDir == null) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
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
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            Files.write(filePath, bodyBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            output.write("HTTP/1.1 201 Created\r\n\r\n".getBytes());
            output.flush();
            return;
        }

        // -----------------------------------------------------------
        // Default → 404
        // -----------------------------------------------------------
        output.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
        output.flush();
    }
}
