import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        // You can use print statements as follows for debugging, they'll be visible when running tests.
        System.out.println("Logs from your program will appear here!");

        try {
            ServerSocket serverSocket = new ServerSocket(4221);

            // Since the tester restarts your program quite often, setting SO_REUSEADDR
            // ensures that we don't run into 'Address already in use' errors
            serverSocket.setReuseAddress(true);

            Socket clientSocket = serverSocket.accept(); // Wait for connection from client.
            System.out.println("accepted new connection");

            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String requestLine = reader.readLine();  // e.g. "GET /echo/abc HTTP/1.1"
            System.out.println("Request Line: " + requestLine);

            // consume remaining request headers until blank line
            String headerLine;
            while ((headerLine = reader.readLine()) != null && headerLine.length() != 0) {
                // ignoring headers for this stage; printing for debug
                System.out.println("Header: " + headerLine);
            }

            String path = "/";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }

            
            OutputStream output = clientSocket.getOutputStream();

            // Handle root path: return 200 with no body (matches stage expectations)
            if ("/".equals(path)) {
                String response = "HTTP/1.1 200 OK\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.UTF_8));

            } else if (path.startsWith("/echo/")) {
                String echo = path.substring("/echo/".length()); // may be empty
                byte[] body = echo.getBytes(StandardCharsets.UTF_8);

                
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
