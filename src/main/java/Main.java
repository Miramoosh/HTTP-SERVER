import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.zip.GZIPOutputStream;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        // Parse --directory <path> argument (optional)
        String directoryArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                directoryArg = args[i + 1];
                break;
            }
        }

        final File baseDir;
        if (directoryArg == null) {
            baseDir = null;
            System.out.println("No --directory provided; continuing without file-serving support.");
        } else {
            File tmp;
            try {
                tmp = new File(directoryArg).getCanonicalFile();
                if (!tmp.isDirectory()) {
                    System.out.println("Provided --directory is not a directory: " + directoryArg);
                    baseDir = null;
                } else {
                    baseDir = tmp;
                    System.out.println("Serving files from: " + baseDir.getAbsolutePath());
                }
            } catch (IOException e) {
                System.out.println("Invalid directory: " + e.getMessage());
                throw new RuntimeException("Failed to resolve directory", e);
            }
        }

        try {
            ServerSocket serverSocket = new ServerSocket(4221);
            serverSocket.setReuseAddress(true);

            // Accept connections forever and handle each connection concurrently
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("accepted new connection");

                new Thread(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

                        String requestLine = reader.readLine(); // e.g. GET /echo/abc HTTP/1.1
                        System.out.println("Request Line: " + requestLine);

                        // Read headers (Content-Length, User-Agent, Accept-Encoding)
                        String headerLine;
                        String userAgent = null;
                        String acceptEncoding = null;
                        int contentLength = 0;

                        while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                            System.out.println("Header: " + headerLine);
                            String lower = headerLine.toLowerCase();

                            if (lower.startsWith("user-agent:")) {
                                int idx = headerLine.indexOf(':');
                                userAgent = headerLine.substring(idx + 1).trim();

                            } else if (lower.startsWith("content-length:")) {
                                int idx = headerLine.indexOf(':');
                                String val = headerLine.substring(idx + 1).trim();
                                try {
                                    contentLength = Integer.parseInt(val);
                                } catch (Exception ignored) {}

                            } else if (lower.startsWith("accept-encoding:")) {
                                int idx = headerLine.indexOf(':');
                                acceptEncoding = headerLine.substring(idx + 1)
                                        .trim().toLowerCase();
                            }
                        }

                        String method = "GET";
                        String path = "/";
                        if (requestLine != null) {
                            String[] parts = requestLine.split(" ");
                            if (parts.length >= 1) method = parts[0];
                            if (parts.length >= 2) path = parts[1];
                        }

                        OutputStream output = clientSocket.getOutputStream();

                        // -----------------------------------------------------------
                        // ROOT: GET /
                        // -----------------------------------------------------------
                        if ("GET".equals(method) && "/".equals(path)) {
                            String response = "HTTP/1.1 200 OK\r\n\r\n";
                            output.write(response.getBytes(StandardCharsets.UTF_8));
                            output.flush();

                            // -----------------------------------------------------------
                            // /echo/{str}  (with gzip compression when accepted)
                            // -----------------------------------------------------------
                        } else if ("GET".equals(method) && path.startsWith("/echo/")) {
                            String echo = path.substring("/echo/".length());
                            byte[] body = echo.getBytes(StandardCharsets.UTF_8);

                            // Robust parsing of Accept-Encoding: handle comma-separated list,
                            // whitespace, and optional parameters (e.g., gzip;q=0.8).
                            boolean gzipSupported = false;
                            if (acceptEncoding != null) {
                                String[] encs = acceptEncoding.split(",");
                                for (String enc : encs) {
                                    String token = enc.trim();
                                    if (token.isEmpty()) continue;
                                    // strip any parameters after ';' (e.g., "gzip;q=0.8")
                                    String main = token.split(";")[0].trim();
                                    if ("gzip".equals(main)) {
                                        gzipSupported = true;
                                        break;
                                    }
                                }
                            }

                            if (gzipSupported) {
                                // Compress the body with gzip
                                byte[] compressed;
                                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                     GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                                    gos.write(body);
                                    gos.finish(); // ensure all data is written
                                    compressed = baos.toByteArray();
                                }

                                StringBuilder headers = new StringBuilder();
                                headers.append("HTTP/1.1 200 OK\r\n");
                                headers.append("Content-Encoding: gzip\r\n");
                                headers.append("Content-Type: text/plain\r\n");
                                headers.append("Content-Length: " + compressed.length + "\r\n");
                                headers.append("\r\n");

                                output.write(headers.toString().getBytes(StandardCharsets.UTF_8));
                                output.write(compressed);
                                output.flush();

                            } else {
                                // No compression: send plain body
                                StringBuilder headers = new StringBuilder();
                                headers.append("HTTP/1.1 200 OK\r\n");
                                headers.append("Content-Type: text/plain\r\n");
                                headers.append("Content-Length: " + body.length + "\r\n");
                                headers.append("\r\n");

                                output.write(headers.toString().getBytes(StandardCharsets.UTF_8));
                                output.write(body);
                                output.flush();
                            }

                            // -----------------------------------------------------------
                            // /user-agent
                            // -----------------------------------------------------------
                        } else if ("GET".equals(method) && "/user-agent".equals(path)) {
                            if (userAgent == null) userAgent = "";

                            byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);

                            String headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: " + body.length + "\r\n" +
                                    "\r\n";

                            output.write(headers.getBytes(StandardCharsets.UTF_8));
                            output.write(body);
                            output.flush();

                            // -----------------------------------------------------------
                            // GET /files/{filename}
                            // -----------------------------------------------------------
                        } else if ("GET".equals(method) && path.startsWith("/files/")) {
                            if (baseDir == null) {
                                String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                output.flush();

                            } else {
                                String filename = path.substring("/files/".length());
                                try {
                                    Path requestedPath = new File(baseDir, filename)
                                            .getCanonicalFile().toPath();

                                    if (!requestedPath.startsWith(baseDir.toPath())
                                            || !Files.exists(requestedPath)
                                            || !Files.isRegularFile(requestedPath)) {

                                        String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                        output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                        output.flush();
                                    } else {
                                        byte[] fileBytes = Files.readAllBytes(requestedPath);

                                        String headers = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: application/octet-stream\r\n" +
                                                "Content-Length: " + fileBytes.length + "\r\n" +
                                                "\r\n";

                                        output.write(headers.getBytes(StandardCharsets.UTF_8));
                                        output.write(fileBytes);
                                        output.flush();
                                    }
                                } catch (IOException e) {
                                    String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                    output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                    output.flush();
                                }
                            }

                            // -----------------------------------------------------------
                            // POST /files/{filename}
                            // -----------------------------------------------------------
                        } else if ("POST".equals(method) && path.startsWith("/files/")) {

                            if (baseDir == null) {
                                String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                output.flush();

                            } else {
                                String filename = path.substring("/files/".length());

                                // Read request body
                                int remaining = contentLength;
                                StringBuilder sb = new StringBuilder();

                                while (remaining > 0) {
                                    char[] buf = new char[Math.min(remaining, 8192)];
                                    int r = reader.read(buf, 0, buf.length);
                                    if (r == -1) break;
                                    sb.append(buf, 0, r);
                                    remaining -= r;
                                }

                                byte[] bodyBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

                                try {
                                    Path requestedPath = new File(baseDir, filename)
                                            .getCanonicalFile().toPath();

                                    if (!requestedPath.startsWith(baseDir.toPath())) {
                                        String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                        output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                        output.flush();

                                    } else {
                                        Files.write(requestedPath, bodyBytes,
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.TRUNCATE_EXISTING);

                                        String created = "HTTP/1.1 201 Created\r\n\r\n";
                                        output.write(created.getBytes(StandardCharsets.UTF_8));
                                        output.flush();
                                    }

                                } catch (IOException e) {
                                    String err = "HTTP/1.1 500 Internal Server Error\r\n\r\n";
                                    output.write(err.getBytes(StandardCharsets.UTF_8));
                                    output.flush();
                                }
                            }

                            // -----------------------------------------------------------
                            // OTHER PATHS → 404
                            // -----------------------------------------------------------
                        } else {
                            String response = "HTTP/1.1 404 Not Found\r\n\r\n";
                            output.write(response.getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }

                    } catch (IOException e) {
                        System.out.println("Handler IOException: " + e.getMessage());
                    } finally {
                        try {
                            clientSocket.close();
                        } catch (IOException e) {
                            System.out.println("Error closing client socket: " + e.getMessage());
                        }
                    }
                }).start();
            }

        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}
