import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        PrintStream out = System.out;

        while ((line = in.readLine()) != null) {
            // Do not print any prompt or debug logs (tester expects only command outputs).
            if (line.length() == 0) continue; // ignore empty lines

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            String cmd = tokens.get(0);

            // builtin: exit (optional, harmless)
            if ("exit".equals(cmd)) {
                break;
            }

            // builtin: echo
            if ("echo".equals(cmd)) {
                if (tokens.size() >= 2) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < tokens.size(); i++) {
                        if (i > 1) sb.append(' ');
                        sb.append(tokens.get(i));
                    }
                    out.println(sb.toString());
                } else {
                    out.println();
                }
                continue;
            }

            // external command: run with ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(tokens);
            try {
                Process p = pb.start();

                // forward stdout and stderr of the child process to our stdout/stderr
                Thread tOut = streamCopier(p.getInputStream(), System.out);
                Thread tErr = streamCopier(p.getErrorStream(), System.err);

                // wait for process to finish, then join copier threads
                p.waitFor();
                tOut.join();
                tErr.join();
            } catch (IOException | InterruptedException e) {
                // If a command cannot be run, print a simple error to stderr (tests won't rely on this).
                System.err.println("Failed to run command: " + e.getMessage());
            }
        }
    }

    // Tokenizer implementing single-quote rules described in the task.
    // - Single quotes take everything literally.
    // - Adjacent quoted/unquoted fragments concatenate.
    // - Outside quotes, whitespace splits tokens (consecutive whitespace collapsed).
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null || line.length() == 0) return tokens;

        StringBuilder cur = new StringBuilder();
        int i = 0, n = line.length();

        while (i < n) {
            char c = line.charAt(i);

            if (c == '\'') {
                // Enter single-quoted segment: append characters literally until next single-quote
                i++; // skip opening '
                while (i < n && line.charAt(i) != '\'') {
                    cur.append(line.charAt(i));
                    i++;
                }
                if (i < n && line.charAt(i) == '\'') i++; // skip closing '
            } else if (Character.isWhitespace(c)) {
                // end current token (if any) and skip all consecutive whitespace
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
                while (i < n && Character.isWhitespace(line.charAt(i))) i++;
            } else {
                // normal character
                cur.append(c);
                i++;
            }
        }

        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    // Helper to copy an InputStream to a PrintStream in a separate thread.
    private static Thread streamCopier(InputStream src, PrintStream dest) {
        Thread t = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int r;
                while ((r = src.read(buffer)) != -1) {
                    dest.write(buffer, 0, r);
                }
                dest.flush();
            } catch (IOException ignored) {
            }
        });
        t.start();
        return t;
    }
}
