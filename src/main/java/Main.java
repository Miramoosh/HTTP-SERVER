import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
                    // still continue but disable file-serving
                    baseDir = null;
                } else {
                    baseDir = tmp;
                    System.out.println("Serving files from: " + baseDir.getAbsolutePath());
                }
            } catch (IOException e) {
                System.out.println("Invalid directory: " + e.getMessage());
                // continue but disable file-serving
                // (do not exit — tests expect server to run without --directory)
                // set baseDir to null to disable /files endpoint
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
                        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        String requestLine = reader.readLine(); // e.g. "GET /path HTTP/1.1"
                        System.out.println("Request Line: " + requestLine);

                        // Read headers (we may or may not use them depending on endpoint)
                        String headerLine;
                        String userAgent = null;
                        while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                            System.out.println("Header: " + headerLine);
                            String lower = headerLine.toLowerCase();
                            if (lower.startsWith("user-agent:")) {
                                int idx = headerLine.indexOf(':');
                                if (idx >= 0 && idx + 1 < headerLine.length()) {
                                    userAgent = headerLine.substring(idx + 1).trim();
                                } else {
                                    userAgent = "";
                                }
                            }
                        }

                        String path = "/";
                        if (requestLine != null) {
                            String[] parts = requestLine.split(" ");
                            if (parts.length >= 2) path = parts[1];
                        }

                        OutputStream output = clientSocket.getOutputStream();

                        // Root path -> minimal 200
                        if ("/".equals(path)) {
                            String response = "HTTP/1.1 200 OK\r\n\r\n";
                            output.write(response.getBytes(StandardCharsets.UTF_8));
                            output.flush();

                            // /echo/{str}
                        } else if (path.startsWith("/echo/")) {
                            String echo = path.substring("/echo/".length());
                            byte[] body = echo.getBytes(StandardCharsets.UTF_8);
                            String headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: " + body.length + "\r\n" +
                                    "\r\n";
                            output.write(headers.getBytes(StandardCharsets.UTF_8));
                            output.write(body);
                            output.flush();

                            // /user-agent
                        } else if ("/user-agent".equals(path)) {
                            if (userAgent == null) userAgent = ""; // defensive: header might be missing
                            byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);
                            String headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: " + body.length + "\r\n" +
                                    "\r\n";
                            output.write(headers.getBytes(StandardCharsets.UTF_8));
                            output.write(body);
                            output.flush();

                            // /files/{filename}
                        } else if (path.startsWith("/files/")) {
                            if (baseDir == null) {
                                // No directory configured -> 404
                                String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                output.flush();
                            } else {
                                String filename = path.substring("/files/".length());
                                try {
                                    // Resolve path safely and prevent directory traversal
                                    Path requestedPath = new File(baseDir, filename).getCanonicalFile().toPath();
                                    Path basePath = baseDir.toPath();

                                    if (!requestedPath.startsWith(basePath) || !Files.exists(requestedPath) || !Files.isRegularFile(requestedPath)) {
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
                                    System.out.println("File handling error: " + e.getMessage());
                                    String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                    output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                    output.flush();
                                }
                            }

                        } else {
                            // Any other path -> 404
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
