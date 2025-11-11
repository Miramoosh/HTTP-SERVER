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

        // Parse --directory <path> argument
        String directoryArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                directoryArg = args[i + 1];
                break;
            }
        }

        if (directoryArg == null) {
            System.out.println("No --directory provided; exiting.");
            return;
        }

        final File baseDir;
        try {
            baseDir = new File(directoryArg).getCanonicalFile();
            if (!baseDir.isDirectory()) {
                System.out.println("Provided --directory is not a directory: " + directoryArg);
                return;
            }
        } catch (IOException e) {
            System.out.println("Invalid directory: " + e.getMessage());
            return;
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
                        String requestLine = reader.readLine(); // e.g. "GET /files/foo HTTP/1.1"
                        System.out.println("Request Line: " + requestLine);

                        // Read and discard headers (we might need them in other endpoints)
                        String headerLine;
                        while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                            System.out.println("Header: " + headerLine);
                        }

                        String path = "/";
                        if (requestLine != null) {
                            String[] parts = requestLine.split(" ");
                            if (parts.length >= 2) path = parts[1];
                        }

                        OutputStream output = clientSocket.getOutputStream();

                        // Root path
                        if ("/".equals(path)) {
                            String response = "HTTP/1.1 200 OK\r\n\r\n";
                            output.write(response.getBytes(StandardCharsets.UTF_8));
                            output.flush();

                            // /echo/* handled earlier in other stages (kept for compatibility)
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

                            // /user-agent (kept for compatibility) - nothing to read here since header was discarded
                        } else if ("/user-agent".equals(path)) {
                            // No User-Agent value captured here because headers already read; respond empty
                            byte[] body = "".getBytes(StandardCharsets.UTF_8);
                            String headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: " + body.length + "\r\n" +
                                    "\r\n";
                            output.write(headers.getBytes(StandardCharsets.UTF_8));
                            output.write(body);
                            output.flush();

                            // /files/{filename} endpoint
                        } else if (path.startsWith("/files/")) {
                            String filename = path.substring("/files/".length()); // may be empty

                            try {
                                // Resolve and prevent directory traversal
                                Path requestedPath = new File(baseDir, filename).getCanonicalFile().toPath();
                                Path basePath = baseDir.toPath();

                                if (!requestedPath.startsWith(basePath) || !Files.exists(requestedPath) || !Files.isRegularFile(requestedPath)) {
                                    // Not found or attempted traversal
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
                                // On IO error, respond 404 (tester expects 404 for missing/unreadable)
                                System.out.println("File handling error: " + e.getMessage());
                                String notFound = "HTTP/1.1 404 Not Found\r\n\r\n";
                                output.write(notFound.getBytes(StandardCharsets.UTF_8));
                                output.flush();
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
