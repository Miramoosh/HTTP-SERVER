import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        try {
            ServerSocket serverSocket = new ServerSocket(4221);
            serverSocket.setReuseAddress(true);

            Socket clientSocket = serverSocket.accept(); // Wait for connection from client.
            System.out.println("accepted new connection");

            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String requestLine = reader.readLine();  // e.g. "GET /user-agent HTTP/1.1"
            System.out.println("Request Line: " + requestLine);

            // Read headers until blank line; capture User-Agent (case-insensitive)
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

            if ("/".equals(path)) {
                String response = "HTTP/1.1 200 OK\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.UTF_8));

            } else if (path.startsWith("/echo/")) {
                String echo = path.substring("/echo/".length());
                byte[] body = echo.getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "\r\n";
                output.write(headers.getBytes(StandardCharsets.UTF_8));
                output.write(body);

            } else if ("/user-agent".equals(path)) {
                if (userAgent == null) userAgent = ""; // defensive: header missing
                byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "\r\n";
                output.write(headers.getBytes(StandardCharsets.UTF_8));
                output.write(body);

            } else {
                String response = "HTTP/1.1 404 Not Found\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }

            clientSocket.close();
            serverSocket.close();

        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}
